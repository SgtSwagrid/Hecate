package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{
  Access, Grant, LinkTarget, Principal, Resource,
}
import slick.dbio.DBIO

/**
  * A store of grants: which principals hold which access over which resources,
  * named in the host's own terms. Access is resolved against groups the caller
  * supplies; [[Permissions]] joins this with [[GroupStore]].
  *
  * Writes are returned as actions to compose into the host's transactions. A
  * principal holds at most one grant per resource only if the caller locks the
  * resource's own row earlier in the same transaction before [[grant]],
  * [[raise]] or [[revokeOver]], as the check-then-insert races under
  * `READ COMMITTED` otherwise. A resource created in the same transaction needs
  * no lock. A duplicate that slips through confers only its highest level, and
  * the next grant replaces it.
  *
  * @param tables
  *   The tables the grants are stored in.
  *
  * @param db
  *   The database to run queries against.
  */
final class GrantStore(tables: AuthTables, db: Transactor):

  import tables.profile.api.*

  private val links = LinkStore(tables)

  /**
    * Grants a principal exactly the given access over a resource, replacing any
    * it held, higher or lower. Locks a person's row, so that a grant and the
    * person's [[AccountStore.delete]] take turns.
    *
    * @param granted
    *   The grant to hold from now on.
    *
    * @return
    *   An action storing the grant, in a transaction joining the caller's.
    */
  def grant(granted: Grant): DBIO[Unit] = lockPerson(granted.principal)
    .flatMap(_ => replace(granted))
    .transactionally

  /**
    * Grants a principal at least the given access over a resource, keeping any
    * higher access it holds. Locks as [[grant]] does.
    *
    * @param granted
    *   The least grant to hold from now on.
    *
    * @return
    *   An action storing the grant unless as much is held already.
    */
  def raise(granted: Grant): DBIO[Unit] = lockPerson(granted.principal)
    .flatMap(_ =>
      held(granted.resource, granted.principal).map(_.access).result,
    )
    .flatMap(levels =>
      if GrantStore.highest(levels).exists(_.includes(granted.access)) then
        DBIO.unit
      else replace(granted),
    )
    .transactionally

  private def lockPerson(principal: Principal): DBIO[Unit] = principal match
    case Principal.Person(id) => tables.lockUser(id).unit
    case Principal.Group(_)   => DBIO.unit

  /** The caller must hold the lock [[grant]] takes. */
  private def replace(granted: Grant): DBIO[Unit] = DBIO.seq(
    held(granted.resource, granted.principal).delete,
    tables.grants += GrantRow.of(granted),
  )

  /**
    * Withdraws a principal's access over a resource.
    *
    * @param resource
    *   The resource to withdraw access over.
    *
    * @param principal
    *   The user or group to withdraw access from.
    *
    * @return
    *   An action withdrawing the grant, doing nothing if there is none.
    */
  def revoke(resource: Resource, principal: Principal): DBIO[Unit] =
    held(resource, principal).delete.unit

  /**
    * Withdraws every grant over a resource and turns off its invite link. No
    * foreign key removes them, so the host must compose this into the
    * transaction deleting the resource, after locking its row.
    *
    * @param resource
    *   The resource being deleted.
    *
    * @return
    *   An action withdrawing every grant over the resource.
    */
  def revokeOver(resource: Resource): DBIO[Unit] = rowsOver(resource)
    .delete
    .flatMap(_ => links.delete(LinkTarget.Sharing(resource, Access.View)))

  /**
    * Withdraws every grant held by any of the given principals.
    *
    * @param principals
    *   The users or groups whose grants to withdraw.
    *
    * @return
    *   An action withdrawing the grants, in the caller's transaction.
    */
  def revokeHeldBy(principals: Seq[Principal]): DBIO[Unit] =
    ifAny(principals)(()): held =>
      tables.grants.filter(heldByAny(_, held)).delete.unit

  /**
    * Finds the resources of which the given principals are, between them, the
    * only holders of `Own`. Any other holder counts, even a group with no
    * members, so this guarantees only that some principal would still hold
    * `Own`, not that anyone could still reach the resource.
    *
    * @param principals
    *   The users and groups about to be deleted together.
    *
    * @return
    *   An action yielding each such resource once, in the caller's transaction.
    */
  def ownedSolelyBy(principals: Seq[Principal]): DBIO[Seq[Resource]] = tables
    .grants
    .filter(mine =>
      heldByAny(mine, principals) && mine.access === Access.Own.code,
    )
    .filterNot(mine =>
      tables
        .grants
        .filter(other =>
          other.resourceKind === mine.resourceKind &&
          other.resourceId === mine.resourceId &&
          other.access === Access.Own.code && !heldByAny(other, principals),
        )
        .exists,
    )
    .map(row => (row.resourceKind, row.resourceId))
    .distinct
    .result
    .map(_.map((kind, id) => Resource(kind, id)))

  /**
    * Finds the resources any of the given principals holds `Own` over.
    *
    * @param principals
    *   The users or groups.
    *
    * @return
    *   An action yielding each such resource once, in ascending order.
    */
  def ownedByAny(principals: Seq[Principal]): DBIO[Seq[Resource]] = ifAny(
    principals,
  )(Seq.empty[Resource]): owners =>
    tables
      .grants
      .filter(row => heldByAny(row, owners) && row.access === Access.Own.code)
      .map(row => (row.resourceKind, row.resourceId))
      .distinct
      .sortBy(identity)
      .result
      .map(_.map((kind, id) => Resource(kind, id)))

  /**
    * The resources of a kind that a user owns and nobody else holds anything
    * over, as a query the host can narrow further.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param kind
    *   The kind of resource, as the host names it.
    *
    * @return
    *   A query of the identifiers of those resources.
    */
  def ownedAlone(user: Long, kind: String): Query[Rep[Long], Long, Seq] =
    val person = Principal.Person(user)
    tables
      .grants
      .filter(mine =>
        mine.resourceKind === kind && heldBy(mine, person) &&
        mine.access === Access.Own.code,
      )
      .filterNot(mine =>
        tables
          .grants
          .filter(other =>
            other.resourceKind === mine.resourceKind &&
            other.resourceId === mine.resourceId && !heldBy(other, person),
          )
          .exists,
      )
      .map(_.resourceId)

  /**
    * Lists the readable grants over a resource.
    *
    * @param resource
    *   The resource whose grants to list.
    *
    * @return
    *   An effect producing the grants.
    */
  def over(resource: Resource): IO[List[Grant]] = db.run(granted(resource))

  /** Runs an action against the store's database, for a resolver over it. */
  private[server] def run[X](action: DBIO[X]): IO[X] = db.run(action)

  /**
    * Lists, in one query, the readable grants over several resources of one
    * kind.
    *
    * @param kind
    *   The kind of the resources, as the host names it.
    *
    * @param ids
    *   The identifiers of the resources.
    *
    * @return
    *   An effect producing the grants.
    */
  def overAll(kind: String, ids: Seq[Long]): IO[List[Grant]] = db.run:
    ifAny(ids.distinct)(List.empty[Grant]): wanted =>
      tables
        .grants
        .filter(row =>
          row.resourceKind === kind && (row.resourceId inSet wanted),
        )
        .result
        .map(_.toList.flatMap(_.toGrant))

  /**
    * Lists the readable grants over a resource within the caller's transaction,
    * such as after locking it.
    *
    * @param resource
    *   The resource whose grants to list.
    *
    * @return
    *   An action yielding the grants.
    */
  def granted(resource: Resource): DBIO[List[Grant]] = rowsOver(resource)
    .result
    .map(_.toList.flatMap(_.toGrant))

  /**
    * Resolves the highest access reaching a user over a resource, through a
    * grant to them or to any of the given groups.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param groups
    *   The user's groups from [[GroupStore.enclosing]], which include every
    *   enclosing group; never direct memberships alone.
    *
    * @param resource
    *   The resource to resolve access over.
    *
    * @return
    *   An effect producing the highest level, or `None` if no grant reaches the
    *   user.
    */
  def access
    (
      user: Long,
      groups: Seq[Long],
      resource: Resource,
    )
    : IO[Option[Access]] = db
    .run(reaching(user, groups).filter(matching(resource)).map(_.access).result)
    .map(GrantStore.highest)

  /**
    * Lists, in one query, the resources of one kind over which a user holds at
    * least the given access.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param groups
    *   The user's groups, as for [[access]].
    *
    * @param kind
    *   The kind of resource, as the host names it.
    *
    * @param least
    *   The lowest level of access that counts.
    *
    * @return
    *   An effect producing the identifiers of the resources.
    */
  def accessible
    (
      user: Long,
      groups: Seq[Long],
      kind: String,
      least: Access,
    )
    : IO[Set[Long]] = db
    .run(
      reaching(user, groups)
        .filter(row =>
          row.resourceKind === kind &&
          (row.access inSet GrantStore.atLeast(least)),
        )
        .map(_.resourceId)
        .distinct
        .result,
    )
    .map(_.toSet)

  /**
    * Resolves, in one query, the highest access a user holds over every
    * resource of one kind that a readable grant reaching them names.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param groups
    *   The user's groups, as for [[access]].
    *
    * @param kind
    *   The kind of resource, as the host names it.
    *
    * @return
    *   An effect producing each resource's identifier mapped to the highest
    *   level the user holds over it.
    */
  def levels
    (
      user: Long,
      groups: Seq[Long],
      kind: String,
    )
    : IO[Map[Long, Access]] = db
    .run(
      reaching(user, groups)
        .filter(_.resourceKind === kind)
        .map(row => (row.resourceId, row.access))
        .result,
    )
    .map(rows =>
      rows
        .groupMap(_._1)(_._2)
        .flatMap((id, stored) => GrantStore.highest(stored).map(id -> _)),
    )

  private def rowsOver(resource: Resource) = tables
    .grants
    .filter(matching(resource))

  private def held(resource: Resource, principal: Principal) =
    rowsOver(resource).filter(heldBy(_, principal))

  private def heldBy(row: tables.Grants, principal: Principal): Rep[Boolean] =
    row.principalKind === principal.kind && row.principalId === principal.id

  /**
    * Whether a grant row is held by any of the principals, with one `IN` per
    * kind.
    */
  private def heldByAny
    (
      row: tables.Grants,
      principals: Seq[Principal],
    )
    : Rep[Boolean] = principals
    .groupMap(_.kind)(_.id)
    .map((kind, ids) =>
      row.principalKind === kind && (row.principalId inSet ids),
    )
    .reduceOption(_ || _)
    .getOrElse(LiteralColumn(false))

  private def matching(resource: Resource)(row: tables.Grants): Rep[Boolean] =
    row.resourceKind === resource.kind && row.resourceId === resource.id

  /** The grants to the user personally or to any of the groups. */
  private def reaching(user: Long, groups: Seq[Long]) = tables
    .grants
    .filter(row =>
      (row.principalKind === Principal.personKind &&
      row.principalId === user) ||
      (row.principalKind === Principal.groupKind &&
      (row.principalId inSet groups)),
    )

object GrantStore:

  /** The highest of the stored levels, ignoring any this version cannot read. */
  private def highest(levels: Iterable[String]): Option[Access] = levels
    .flatMap(Access.fromCode)
    .maxOption

  /**
    * The stored codes of every level including the given one, so the database
    * can compare levels stored by code.
    */
  private def atLeast(least: Access): Seq[String] = Access
    .values
    .filter(_.includes(least))
    .map(_.code)
    .toSeq
