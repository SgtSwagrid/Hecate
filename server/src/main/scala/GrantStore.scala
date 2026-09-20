package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{Access, Grant, Principal, Resource}
import slick.dbio.DBIO

/**
  * The store of grants: which principals hold which access over which
  * resources. Resources are named in the host application's own terms, so this
  * store never knows what they are.
  *
  * Access is resolved against whichever groups the caller supplies, so that
  * this store needs no [[GroupStore]], and group membership is walked once, by
  * [[GroupStore.groupIdsOf]], and never again here. [[Permissions]] joins the
  * two.
  *
  * Writes are returned as actions rather than run, so that a host application
  * can grant ownership in the same transaction that creates a resource, and
  * withdraw every grant in the same transaction that deletes one.
  *
  * @param tables
  *   The tables the grants are stored in.
  *
  * @param db
  *   The database to run the queries against.
  */
final class GrantStore(tables: AuthTables, db: Transactor):

  import tables.profile.api.*

  /**
    * Grants one principal exactly the given access over one resource, replacing
    * whatever access they already held over it, whether higher or lower. Runs
    * as one transaction, which joins the caller's if there is one.
    *
    * At most one grant per principal per resource is guaranteed only when the
    * caller has locked the resource's own row earlier in the same transaction.
    * No table can declare the key that would otherwise enforce it (see
    * [[AuthTables]]), and under `READ COMMITTED` two unlocked grants racing for
    * the same principal can both find nothing to replace and both insert. The
    * resource belongs to the host application, so only the host can lock it. A
    * resource created in the same transaction needs no lock, since no other
    * transaction can see it yet. A duplicate that does slip through is
    * harmless: resolution takes the highest level, revocation removes every
    * matching row, and the next grant to that principal replaces them all.
    *
    * Deleting the resource must take the same lock before calling
    * [[revokeAll]], for the same reason: a grant written concurrently would
    * otherwise commit after the revocation and outlive the resource.
    *
    * @param granted
    *   The grant to hold from now on.
    *
    * @return
    *   An action storing the grant.
    */
  def grant(granted: Grant): DBIO[Unit] = DBIO
    .seq(
      lockPerson(granted.principal),
      held(granted.resource, granted.principal).delete,
      tables.grants += GrantRow.of(granted),
    )
    .transactionally

  /**
    * Locks the row of the person granted to, if the principal is a person, so
    * that a grant and the deletion of that person's account take turns rather
    * than leaving a grant that outlives the account.
    */
  private def lockPerson(principal: Principal): DBIO[Unit] = principal match
    case Principal.Person(id) =>
      tables.users.filter(_.id === id).forUpdate.result.unit
    case Principal.Group(_) => DBIO.successful(())

  /**
    * Withdraws whatever access one principal holds over one resource, leaving
    * every other principal's grants over it in place.
    *
    * @param resource
    *   The resource to withdraw access over.
    *
    * @param principal
    *   The user or group to withdraw access from.
    *
    * @return
    *   An action withdrawing the grant, which does nothing if there is none.
    */
  def revoke(resource: Resource, principal: Principal): DBIO[Unit] =
    held(resource, principal).delete.unit

  /**
    * Withdraws every grant over one resource. No foreign key will ever remove a
    * grant naming a resource that no longer exists, so a host application must
    * compose this into the transaction deleting the resource itself. Opens no
    * transaction of its own. The caller must lock the resource's row before
    * calling this, not merely when deleting the row itself, or a grant being
    * written concurrently can commit after it.
    *
    * @param resource
    *   The resource being deleted.
    *
    * @return
    *   An action withdrawing every grant over the resource.
    */
  def revokeAll(resource: Resource): DBIO[Unit] = over(resource).delete.unit

  /**
    * Withdraws every grant held by any of the given principals, over any
    * resource, so that nothing a deleted group held outlives it. Composes into
    * the caller's transaction and opens none of its own. Given no principals,
    * it does nothing, and never asks the database about an empty set.
    *
    * @param principals
    *   The users or groups whose grants to withdraw.
    *
    * @return
    *   An action withdrawing every such grant.
    */
  def revokeHeldBy(principals: Seq[Principal]): DBIO[Unit] =
    ifAny(principals)(()): held =>
      tables.grants.filter(heldByAny(_, held)).delete.unit

  /**
    * The resources over which the given principals, between them, are the only
    * holders of `Own`, so that deleting all of them at once would leave each
    * with no owner at all. Any other holder, a group included, counts. Composes
    * into the caller's transaction and opens none of its own, so that a
    * deletion can refuse before removing anything.
    *
    * A surviving holder is a holder of the grant, not necessarily a person who
    * can act on it: a group with no members at all counts, and so does one
    * whose only members are being deleted alongside. What this guarantees is
    * that some principal still holds `Own`, not that somebody can still reach
    * the resource. Whether that is enough is the host application's to decide,
    * since only it knows what its resources are worth.
    *
    * @param principals
    *   The users and groups about to be deleted together, such as a user and
    *   every group they own.
    *
    * @return
    *   An action yielding every such resource, each once, possibly none.
    */
  def soleOwnerOf(principals: Seq[Principal]): DBIO[Seq[Resource]] = tables
    .grants
    .filter(mine =>
      heldByAny(mine, principals) && mine.access === Access.Own.name,
    )
    .filterNot(mine =>
      tables
        .grants
        .filter(other =>
          other.resourceKind === mine.resourceKind &&
          other.resourceId === mine.resourceId &&
          other.access === Access.Own.name && !heldByAny(other, principals),
        )
        .exists,
    )
    .map(row => (row.resourceKind, row.resourceId))
    .distinct
    .result
    .map(_.map((kind, id) => Resource(kind, id)))

  /**
    * Lists every readable grant over one resource, for showing who holds access
    * to it and for checking directly what was stored.
    */
  def grantsOver(resource: Resource): IO[List[Grant]] = db
    .run(over(resource).result)
    .map(_.toList.flatMap(_.toGrant))

  /**
    * As [[grantsOver]], but composing into the caller's transaction, so that a
    * host can read who holds a resource after locking it and before changing
    * its grants, and refuse a change that would leave it with no owner.
    */
  def granted(resource: Resource): DBIO[List[Grant]] = over(resource)
    .result
    .map(_.toList.flatMap(_.toGrant))

  /**
    * The highest access that reaches one user over one resource, through a
    * grant to them personally or to any of the given groups.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param groups
    *   The identifiers of every group the user effectively belongs to. These
    *   must come from [[GroupStore.groupIdsOf]], which includes every enclosing
    *   group, and never from [[GroupStore.memberships]], which lists direct
    *   memberships only and would let a grant to an enclosing group miss them.
    *
    * @param resource
    *   The resource to resolve access over.
    *
    * @return
    *   A highest level of access reaching the user, or `None` if no grant does.
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
    * The identifiers of every resource of one kind over which one user holds at
    * least the given access, resolved in a single query, which asks the
    * database for the grants that are high enough rather than reading every
    * grant that reaches the user and sifting them here.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param groups
    *   The identifiers of every group the user effectively belongs to, from
    *   [[GroupStore.groupIdsOf]] and never [[GroupStore.memberships]], as for
    *   [[access]].
    *
    * @param kind
    *   The kind of resource to list, as named by the host application.
    *
    * @param least
    *   The lowest level of access that counts.
    *
    * @return
    *   A set of the identifiers of every such resource, possibly empty.
    */
  def visible
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
    * The highest access one user holds over every resource of one kind that a
    * readable grant reaching them names, resolved in a single query.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param groups
    *   The identifiers of every group the user effectively belongs to, from
    *   [[GroupStore.groupIdsOf]] and never [[GroupStore.memberships]], as for
    *   [[access]].
    *
    * @param kind
    *   The kind of resource to resolve, as named by the host application.
    *
    * @return
    *   A map from the identifier of each such resource to the highest level of
    *   access the user holds over it. A resource no readable grant reaches is
    *   absent from it.
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

  /** The query for every grant over one resource. */
  private def over(resource: Resource) = tables
    .grants
    .filter(matching(resource))

  /** The query for one principal's grant over one resource. */
  private def held(resource: Resource, principal: Principal) = over(resource)
    .filter(heldBy(_, principal))

  /** Whether a grant row is held by the given principal. */
  private def heldBy(row: tables.Grants, principal: Principal): Rep[Boolean] =
    row.principalKind === principal.kind && row.principalId === principal.id

  /**
    * Whether a grant row is held by any of the given principals, asked one term
    * per kind rather than one per principal, so that a long list of users is a
    * single `IN`. No principals at all is nobody.
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

  /** Whether a grant row is over the given resource. */
  private def matching(resource: Resource)(row: tables.Grants): Rep[Boolean] =
    row.resourceKind === resource.kind && row.resourceId === resource.id

  /**
    * The query for every grant reaching one user: those to them personally, and
    * those to any of the given groups. A user and a group sharing an identifier
    * are told apart by the kind of principal, never confused. Given no groups,
    * only personal grants are sought, without asking about an empty set.
    */
  private def reaching(user: Long, groups: Seq[Long]) = tables
    .grants
    .filter: row =>
      val personal = row.principalKind === Principal.personKind &&
        row.principalId === user
      if groups.isEmpty then personal
      else
        personal ||
        (row.principalKind === Principal.groupKind &&
        (row.principalId inSet groups))

object GrantStore:

  /**
    * The highest of the given stored levels of access. A level this version
    * does not recognise is ignored, so that an unreadable grant confers nothing
    * rather than being guessed at.
    */
  private def highest(levels: Iterable[String]): Option[Access] = levels
    .flatMap(Access.fromName)
    .maxOption

  /**
    * The stored names of every level of access that includes the given one, for
    * asking the database itself which grants are high enough. Levels are stored
    * by name and compared by position, so the comparison travels as the set of
    * names that satisfy it. A name this version does not recognise is not among
    * them, so an unreadable grant confers nothing, exactly as in [[highest]].
    */
  private def atLeast(least: Access): Seq[String] = Access
    .values
    .filter(_.includes(least))
    .map(_.name)
    .toSeq
