package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{
  Access, AuthRefusal, Grant, Group, GroupDetails, Invitation, JoinableGroup,
  LinkTarget, Membership, OwnedGroup, Principal, Resource, User,
}
import slick.dbio.DBIO

/**
  * A store of user groups, their memberships, invitations and requests to join.
  *
  * A group is managed by its owner alone. Its direct members see it, its owner
  * and one another, but only the owner sees who is invited or asking to join.
  * Nobody joins without both their own and the owner's agreement: by accepting
  * an invitation, by having a request admitted, or by following an invite link.
  * A request may only be made of a group the asker can see, a public one or one
  * nested inside a group they are a member of; any other is reported as
  * missing. A group never contains itself, and every walk of the tree visits
  * each group once.
  *
  * Every change to a group's members, invitations, requests or invite link
  * first locks the group's row, as its check-then-insert races under
  * `READ COMMITTED` otherwise. Any new change of this kind must take the same
  * lock. Several groups are locked in one statement in ascending order of
  * identifier, so that two transactions cannot deadlock.
  *
  * @param tables
  *   The tables to store the groups in.
  *
  * @param db
  *   The database to run queries against.
  *
  * @param cascade
  *   The hook deleting the host's rows for groups about to be deleted, run in
  *   the same transaction before the groups go. The groups' grants are revoked
  *   by this store.
  *
  * @param resources
  *   The lookup of a resource's name that locks its row until the transaction
  *   ends, yielding `None` for a missing resource, as for [[LinkService]]. A
  *   deleted group's resources pass to its owner under these locks. The default
  *   locks nothing and finds every resource.
  */
final class GroupStore
  (
    tables: AuthTables,
    db: Transactor,
    cascade: Seq[Principal] => DBIO[Unit] = _ => DBIO.unit,
    resources: Resource => DBIO[Option[String]] = GroupStore.everywhere,
  ):

  import tables.profile.api.*

  private type Pairs = Query[(Rep[Long], Rep[Long]), (Long, Long), Seq]

  private val grants = GrantStore(tables, db)

  private val links = LinkStore(tables)

  private val permissions = Permissions(this, grants)

  private val memberPairs: Pairs = tables
    .members
    .map(row => (row.groupId, row.userId))

  private val invitationPairs: Pairs = tables
    .invitations
    .map(row => (row.groupId, row.userId))

  private val requestPairs: Pairs = tables
    .requests
    .map(row => (row.groupId, row.userId))

  /**
    * Lists the groups a user owns, each with its direct members, invitees,
    * applicants and invite link.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @return
    *   An effect producing the groups, in order of creation.
    */
  def owned(owner: Long): IO[List[OwnedGroup]] = db.run:
    for
      groups <- ownedBy(owner).sortBy(_.id).result
      ids = groups.map(_.id)
      enrolled   <- usersIn(memberPairs, ids)
      invited    <- usersIn(invitationPairs, ids)
      applicants <- usersIn(requestPairs, ids)
      codes      <- links.toGroups(ids)
    yield groups
      .map(group =>
        OwnedGroup(
          group.toGroup,
          enrolled.getOrElse(group.id, List.empty),
          invited.getOrElse(group.id, List.empty),
          applicants.getOrElse(group.id, List.empty),
          codes.get(group.id),
        ),
      )
      .toList

  /**
    * Lists the groups a user is directly a member of, each with its owner and
    * direct members. Enclosing groups reached by [[enclosing]] are left out, as
    * the user cannot leave a group they never joined.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the memberships.
    */
  def memberships(user: Long): IO[List[Membership]] = db.run:
    for
      joined <- membershipsOf(user)
        .join(tables.users)
        .on(_._2.ownerId === _.id)
        .map((row, owner) => (row._2, owner))
        .sortBy(_._1.id)
        .result
      enrolled <- usersIn(memberPairs, joined.map(_._1.id))
    yield joined
      .map((group, owner) =>
        Membership(
          group.toGroup,
          owner.toUser,
          enrolled.getOrElse(group.id, List.empty),
        ),
      )
      .toList

  /** The direct members of each group, by username; empty groups are absent. */
  private def usersIn
    (pairs: Pairs, groups: Seq[Long])
    : DBIO[Map[Long, List[User]]] = ifAny(groups)(Map.empty): shown =>
    pairs
      .filter(_._1 inSet shown)
      .join(tables.users)
      .on(_._2 === _.id)
      .map((pair, user) => (pair._1, user))
      .result
      .map(GroupStore.byGroup)

  /** Never asks the database about no groups. */
  private def pairsIn
    (pairs: Pairs, groups: Seq[Long])
    : DBIO[Seq[(Long, Long)]] =
    ifAny(groups)(Seq.empty)(shown => pairs.filter(_._1 inSet shown).result)

  private def groupsWith(pairs: Pairs, user: Long): DBIO[Set[Long]] = pairs
    .filter(_._2 === user)
    .map(_._1)
    .result
    .map(_.toSet)

  /**
    * Lists the pending invitations sent to a user, withholding each group's
    * parent.
    *
    * @param user
    *   The identifier of the invitee.
    *
    * @return
    *   An effect producing the invitations, each with its group and owner.
    */
  def invitations(user: Long): IO[List[Invitation]] = db
    .run(
      tables
        .invitations
        .filter(_.userId === user)
        .join(tables.groups)
        .on(_.groupId === _.id)
        .join(tables.users)
        .on(_._2.ownerId === _.id)
        .sortBy(_._1._1.id)
        .result,
    )
    .map(
      _.map { case ((invitation, group), owner) =>
          Invitation(
            invitation.id,
            Group(group.id, group.name),
            owner.toUser,
          )
        }
        .toList,
    )

  /**
    * Lists the groups a user may ask to join: every public group and every
    * group nested inside one they are a member of, except those they own,
    * belong to or are invited to. Groups they have asked to join are always
    * included, so they can withdraw the request. A parent they cannot see is
    * withheld.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the groups by name, each with its owner and whether
    *   the user has asked already.
    */
  def joinable(user: Long): IO[List[JoinableGroup]] = db.run:
    for
      direct  <- directOf(user)
      parents <- forest(direct.map(_._2))
      joined = direct.map(_._1).toSet
      nested = GroupStore.withDescendants(joined.toSeq, parents)
      asked   <- groupsWith(requestPairs, user)
      invited <- groupsWith(invitationPairs, user)
      found   <- findable(nested ++ asked)
        .filterNot(_.ownerId === user)
        .join(tables.users)
        .on(_.ownerId === _.id)
        .sortBy(_._1.name)
        .result
      offered =
        found.filterNot((group, _) => joined(group.id) || invited(group.id))
      // The groups they joined are nested too, as a walk includes its roots.
      seen = nested ++ offered.map(_._1.id)
    yield offered
      .map((group, owner) =>
        JoinableGroup(
          group.toGroup.copy(parentId = group.parentId.filter(seen)),
          owner.toUser,
          asked(group.id),
        ),
      )
      .toList

  private def findable(groups: Set[Long]) = tables
    .groups
    .filter(group => group.public || (group.id inSet groups))

  /** The groups a user is directly a member of, each with its owner. */
  private def directOf(user: Long): DBIO[Seq[(Long, Long)]] =
    membershipsOf(user).map(row => (row._1.groupId, row._2.ownerId)).result

  /**
    * Lists every group a user effectively belongs to: their memberships plus
    * every ancestor of those. Membership propagates upwards only, so anything
    * addressed to a group reaches the members of the groups nested in it, and
    * never the reverse. Invitations and requests count for nothing.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the groups' identifiers in ascending order.
    */
  def enclosing(user: Long): IO[List[Long]] = db.run:
    for
      direct  <- directOf(user)
      parents <- forest(direct.map(_._2))
    yield GroupStore.withAncestors(direct.map(_._1), parents)

  /**
    * Every group of the given owners mapped to its parent. A group nests only
    * inside a group of the same owner, so this holds every ancestor and
    * descendant.
    */
  private def forest(owners: Seq[Long]): DBIO[Map[Long, Option[Long]]] = ifAny(
    owners.distinct,
  )(Map.empty): distinct =>
    tables
      .groups
      .filter(_.ownerId inSet distinct)
      .map(group => (group.id, group.parentId))
      .result
      .map(_.toMap)

  /**
    * Lists every member of some groups or of any group nested inside them: the
    * reverse of [[enclosing]].
    *
    * @param groups
    *   The identifiers of the groups.
    *
    * @return
    *   An effect producing the users' identifiers in ascending order.
    */
  def membersWithin(groups: Seq[Long]): IO[List[Long]] =
    if groups.isEmpty then IO.pure(List.empty)
    else db.run(membersBeneath(groups))

  /** As [[membersWithin]], in the caller's transaction. */
  private[server] def membersBeneath(groups: Seq[Long]): DBIO[List[Long]] =
    ifAny(groups)(List.empty[Long]): some =>
      for
        owners  <- tables.groups.filter(_.id inSet some).map(_.ownerId).result
        parents <- forest(owners)
        within = GroupStore.withDescendants(some, parents)
        members <- tables
          .members
          .filter(_.groupId inSet within)
          .map(_.userId)
          .distinct
          .sortBy(identity)
          .result
      yield members.toList

  /**
    * Finds everyone who may address a user: the owners of the groups the user
    * is a member of.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the owners' identifiers.
    */
  def addressersOf(user: Long): IO[Set[Long]] = db
    .run(membershipsOf(user).map(_._2.ownerId).distinct.result)
    .map(_.toSet)

  /**
    * Names groups, for a host to say whom something was addressed to.
    *
    * @param groups
    *   The identifiers of the groups.
    *
    * @return
    *   An effect producing the name of each group that still exists, by
    *   identifier.
    */
  def names(groups: Seq[Long]): IO[Map[Long, String]] = db.run:
    ifAny(groups.distinct)(Map.empty): wanted =>
      tables
        .groups
        .filter(_.id inSet wanted)
        .map(group => (group.id, group.name))
        .result
        .map(_.toMap)

  /**
    * Creates a group.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @param details
    *   The group's name, and its parent, which the owner must own.
    *
    * @return
    *   An effect producing the stored group.
    */
  def create(owner: Long, details: GroupDetails): IO[Group] =
    // Locked so the parent cannot be deleted from under its new child.
    holding(details.parentId.toSeq): held =>
      for
        _  <- ensureParent(held, owner, details.parentId)
        id <- tables.groups.returning(tables.groups.map(_.id)) +=
          GroupRow(0, owner, details.name, details.parentId)
      yield Group(id, details.name, details.parentId)

  /**
    * Renames or moves a group, refusing a move inside its own subtree.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @param id
    *   The identifier of the group.
    *
    * @param details
    *   The group's new name and parent.
    */
  def update
    (
      owner: Long,
      id: Long,
      details: GroupDetails,
    )
    : IO[Unit] = holding(id +: details.parentId.toSeq): held =>
    for
      _ <- ensureOwner(held, owner, id)
      _ <- ensureParent(held, owner, details.parentId)
      _ <- ensureAcyclic(owner, id, details.parentId)
      _ <- tables
        .groups
        .filter(_.id === id)
        .map(group => (group.name, group.parentId))
        .update((details.name, details.parentId))
    yield ()

  /**
    * Deletes a group and every group nested beneath it, with their memberships,
    * invitations, requests, links, grants and the host's rows. Whatever they
    * alone owned passes to the owner, so that nothing is left without one.
    *
    * Locks are taken in the order sharing and account deletion take them: the
    * resources the groups own (through `resources`, found before the groups are
    * locked, and any found since locked after), then the owner's row, then the
    * owner's groups. Sole ownership is checked again under those locks.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @param id
    *   The identifier of the group.
    *
    * @return
    *   An effect producing each resource passed to the owner, with everyone its
    *   grants reached before.
    */
  def delete(owner: Long, id: Long): IO[List[(Resource, Set[Long])]] = db.run(
    (
      for
        guessed <- ownedBy(owner).result.map(GroupStore.owned(_, id))
        locked  <- lockResources(guessed, Set.empty)
        _       <- tables.locked(tables.users.filter(_.id === owner)).result
        // All the owner's groups are read under lock, so the subtree includes
        // any group nested beneath it concurrently.
        groups <- tables.locked(ownedBy(owner).sortBy(_.id)).result
        _      <- refuseUnless(
          groups.exists(_.id == id),
          AuthRefusal.GroupMissing,
        )
        doomed = GroupStore.owned(groups, id)
        _      <- lockResources(doomed, locked)
        handed <- handOver(owner, doomed)
        _      <- deleteGroups(doomed)
      yield handed
    ).transactionally,
  )

  /**
    * Invites a user to a group. Inviting a member or invitee changes nothing;
    * inviting an applicant admits them, and an owner inviting themselves joins.
    * An unknown username is refused as such, so callers can tell which names
    * were mistyped.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param username
    *   The invitee's username.
    *
    * @return
    *   An effect producing the invitee.
    */
  def invite(owner: Long, group: Long, username: String): IO[User] =
    managing(owner, group):
      for
        found <- tables
          .users
          .filter(_.usernameKey === Username.key(username))
          .result
          .headOption
        user <- required(
          found,
          AuthRefusal.UserMissing(username),
        )
        _ <- offer(owner, group, user.id)
      yield user.toUser

  /**
    * Accepts an invitation, making its invitee a member of the group.
    *
    * @param user
    *   The identifier of the invitee.
    *
    * @param invitation
    *   The identifier of the invitation.
    */
  def accept(user: Long, invitation: Long): IO[Unit] = db.run((for
    found <- invitationOf(user, invitation).result.headOption
    row   <- required(found, AuthRefusal.InvitationMissing)
    _     <- lock(Seq(row.groupId))
    // Deleted under the lock, which both rechecks and claims the invitation.
    taken <- invitationOf(user, invitation).delete
    _     <- refuseUnless(
      taken == 1,
      AuthRefusal.InvitationMissing,
    )
    _ <- enrol(row.groupId, user)
  yield ()).transactionally)

  /**
    * Declines an invitation, deleting it. A single statement, so a racing
    * acceptance finds nothing.
    *
    * @param user
    *   The identifier of the invitee.
    *
    * @param invitation
    *   The identifier of the invitation.
    */
  def decline(user: Long, invitation: Long): IO[Unit] = db.run(
    invitationOf(user, invitation)
      .delete
      .flatMap(removed =>
        refuseUnless(
          removed == 1,
          AuthRefusal.InvitationMissing,
        ),
      ),
  )

  /**
    * Takes a user out of a group, doing nothing if they are not a member.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param group
    *   The identifier of the group.
    */
  def leave(user: Long, group: Long): IO[Unit] =
    holding(Seq(group))(_ => memberOf(group, user).delete.unit)

  /**
    * Makes the owner of a group a member of it, doing nothing if they are one.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @param group
    *   The identifier of the group.
    */
  def join(owner: Long, group: Long): IO[Unit] =
    managing(owner, group)(enrol(group, owner))

  /**
    * Asks for a user to join a group they can see, for its owner to admit or
    * decline. Asking twice or as a member changes nothing; asking when invited
    * accepts, and asking to join one's own group joins.
    *
    * @param user
    *   The identifier of the user asking.
    *
    * @param group
    *   The identifier of the group.
    */
  def request(user: Long, group: Long): IO[Unit] = holding(Seq(group)): held =>
    for
      row <- required(
        held.headOption,
        AuthRefusal.GroupMissing,
      )
      ready <-
        if row.ownerId == user then DBIO.successful(true)
        else invitationTo(group, user).exists.result
      _ <- if ready then enrol(group, user) else ask(row, user)
    yield ()

  /**
    * Withdraws a user's request to join a group, doing nothing if there is
    * none.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param group
    *   The identifier of the group.
    */
  def withdraw(user: Long, group: Long): IO[Unit] =
    holding(Seq(group))(_ => requestFrom(group, user).delete.unit)

  /**
    * Admits an applicant to a group. Admitting a member changes nothing, and
    * anyone else who has not asked is refused.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param user
    *   The identifier of the applicant.
    */
  def admit(owner: Long, group: Long, user: Long): IO[Unit] =
    managing(owner, group):
      for
        present <- memberOf(group, user).exists.result
        asked   <- requestFrom(group, user).exists.result
        _       <-
          if present then DBIO.unit
          else if asked then enrol(group, user)
          else DBIO.failed(AuthProblem(AuthRefusal.RequestMissing))
      yield ()

  /**
    * Makes a group public, so anyone can find it and ask to join, or private.
    * Pending requests stand either way.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param public
    *   Whether the group is to be public.
    */
  def setPublic(owner: Long, group: Long, public: Boolean): IO[Unit] =
    managing(owner, group):
      tables.groups.filter(_.id === group).map(_.public).update(public).unit

  /**
    * Gives a group an invite link unless it has one.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @param group
    *   The identifier of the group.
    *
    * @return
    *   An effect producing the link's code.
    */
  def link(owner: Long, group: Long): IO[String] =
    managing(owner, group)(links.ensure(owner, LinkTarget.Joining(group)))

  /**
    * Replaces a group's invite link with one of a new code.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @param group
    *   The identifier of the group.
    *
    * @return
    *   An effect producing the new code.
    */
  def relink(owner: Long, group: Long): IO[String] =
    managing(owner, group)(links.renew(owner, LinkTarget.Joining(group)))

  /**
    * Turns off a group's invite link.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @param group
    *   The identifier of the group.
    */
  def unlink(owner: Long, group: Long): IO[Unit] =
    managing(owner, group)(links.delete(LinkTarget.Joining(group)))

  /**
    * Enrols a user in the group a link leads to, provided it still does once
    * the group is locked. Composes into the caller's transaction.
    */
  private[server] def follow
    (user: Long, group: Long, code: String)
    : DBIO[Unit] =
    for
      _     <- lock(Seq(group))
      found <- links.find(code)
      _     <-
        if found.flatMap(_._1.target).contains(LinkTarget.Joining(group)) then
          enrol(group, user)
        else DBIO.failed(AuthProblem(AuthRefusal.LinkMissing))
    yield ()

  /** A group's name and whether the user is a member, or `None` if missing. */
  private[server] def glance
    (user: Long, group: Long)
    : DBIO[Option[(String, Boolean)]] = tables
    .groups
    .filter(_.id === group)
    .map(row => (row.name, memberOf(group, user).exists))
    .result
    .headOption

  /**
    * Who sees anything of some groups and what their members belong to through
    * them. Missing groups concern nobody, so ask before deleting.
    */
  private[server] def surroundings
    (groups: Seq[Long])
    : IO[GroupStore.Surroundings] = db.run:
    for
      rows <- ifAny(groups.distinct)(Seq.empty[GroupRow])(ids =>
        tables.groups.filter(_.id inSet ids).result,
      )
      ids    = rows.map(_.id)
      owners = rows.map(_.ownerId).toSet
      parents <- forest(rows.map(_.ownerId))
      enclosing = GroupStore.withAncestors(ids, parents)
      enrolled   <- pairsIn(memberPairs, enclosing)
      invitees   <- pairsIn(invitationPairs, ids)
      applicants <- pairsIn(requestPairs, ids)
      direct = ids.toSet
    yield GroupStore.Surroundings(
      owners,
      enrolled.collect { case (group, user) if direct(group) => user }.toSet,
      owners ++ (enrolled ++ invitees ++ applicants).map(_._2),
      rows.exists(_.public),
      enclosing.toSet,
    )

  /** The group and its descendants if the user owns it, or else nothing. */
  private[server] def subtreeOf(owner: Long, group: Long): IO[Seq[Long]] = db
    .run(ownedBy(owner).result)
    .map(GroupStore.owned(_, group))

  private[server] def invitedTo(user: Long, invitation: Long): IO[Seq[Long]] =
    db.run(invitationOf(user, invitation).map(_.groupId).result)

  /**
    * Removes a member, invitee or applicant from a group.
    *
    * @param owner
    *   The identifier of the owner.
    *
    * @param group
    *   The identifier of the group.
    *
    * @param user
    *   The identifier of the person to remove.
    */
  def remove(owner: Long, group: Long, user: Long): IO[Unit] =
    managing(owner, group):
      DBIO.seq(
        memberOf(group, user).delete,
        invitationTo(group, user).delete,
        requestFrom(group, user).delete,
      )

  /**
    * Locks, in ascending order, the resources the given groups own that are not
    * locked already.
    *
    * @return
    *   An action yielding every resource now locked.
    */
  private def lockResources
    (doomed: Seq[Long], locked: Set[Resource])
    : DBIO[Set[Resource]] = grants
    .ownedByAny(doomed.map(Principal.Group(_)))
    .flatMap(owned =>
      DBIO
        .sequence(owned.filterNot(locked).map(resources))
        .map(_ => locked ++ owned),
    )

  /**
    * Grants the owner `Own` over every resource still standing that the given
    * groups, which the caller holds locked with the resources, alone own.
    */
  private def handOver
    (owner: Long, doomed: Seq[Long])
    : DBIO[List[(Resource, Set[Long])]] = grants
    .ownedSolelyBy(doomed.map(Principal.Group(_)))
    .flatMap(orphaned =>
      DBIO
        .sequence(
          orphaned
            .toList
            .map(resource =>
              resources(resource).flatMap(
                _.fold(DBIO.successful(Option.empty[(Resource, Set[Long])]))(
                  _ =>
                    for
                      formerly <- permissions.holding(resource)
                      _        <- grants.grant(Grant(
                        resource,
                        Principal.Person(owner),
                        Access.Own,
                      ))
                    yield Some(resource -> formerly),
                ),
              ),
            ),
        )
        .map(_.flatten),
    )

  /**
    * Deletes groups the caller already holds locked, and all that goes with
    * them.
    */
  private def deleteGroups(doomed: Seq[Long]): DBIO[Unit] =
    ifAny(doomed)(()): gone =>
      val principals = gone.map(Principal.Group(_))
      for
        _ <- tables.members.filter(_.groupId inSet gone).delete
        _ <- tables.invitations.filter(_.groupId inSet gone).delete
        _ <- tables.requests.filter(_.groupId inSet gone).delete
        _ <- links.deleteToGroups(gone)
        _ <- grants.revokeHeldBy(principals)
        _ <- cascade(principals)
        _ <- tables.groups.filter(_.id inSet gone).delete
      yield ()

  /**
    * Removes a user from the groups for their account's deletion: the groups
    * they own, their memberships, invitations and requests, and every invite
    * link they made.
    */
  private[server] def forget(user: Long): DBIO[Unit] =
    for
      owned <- lockOwnedBy(user)
      _     <- deleteGroups(owned.map(_.id))
      _     <- tables.members.filter(_.userId === user).delete
      _     <- tables.invitations.filter(_.userId === user).delete
      _     <- tables.requests.filter(_.userId === user).delete
      _     <- links.deleteMadeBy(user)
    yield ()

  /**
    * Lists the principals a user may address: themselves, the groups they own,
    * and those groups' accepted members. Invitees are excluded, as addressing
    * someone gives them something, which needs a relation they agreed to.
    *
    * @param user
    *   The identifier of the user.
    *
    * @return
    *   An effect producing the principals, the user first.
    */
  def addressable(user: Long): IO[List[Principal]] = db.run:
    for
      owned   <- ownedBy(user).sortBy(_.id).map(_.id).result
      members <- tables
        .members
        .filter(_.groupId inSet owned)
        .join(tables.users)
        .on(_.userId === _.id)
        .map(_._2.id)
        .distinct
        .sortBy(identity)
        .result
    yield (Principal.Person(user) +:
      (owned.map(Principal.Group(_)) ++
        members.filterNot(_ == user).map(Principal.Person(_)))).toList

  /**
    * Checks, in one query, whether [[addressable]] would list a principal.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param principal
    *   The principal to address.
    *
    * @return
    *   An effect producing whether the user may address the principal.
    */
  def mayAddress(user: Long, principal: Principal): IO[Boolean] =
    principal match
      case Principal.Person(id) if id == user => IO.pure(true)
      case Principal.Person(id)               =>
        db.run(membershipsOf(id).filter(_._2.ownerId === user).exists.result)
      case Principal.Group(id) => db.run(owns(user, id))

  private def ownedBy(owner: Long) = tables.groups.filter(_.ownerId === owner)

  private def membershipsOf(user: Long) = tables
    .members
    .filter(_.userId === user)
    .join(tables.groups)
    .on(_.groupId === _.id)

  private def memberOf(group: Long, user: Long) = tables
    .members
    .filter(member => member.groupId === group && member.userId === user)

  private def invitationTo(group: Long, user: Long) = tables
    .invitations
    .filter(invite => invite.groupId === group && invite.userId === user)

  private def requestFrom(group: Long, user: Long) = tables
    .requests
    .filter(asking => asking.groupId === group && asking.userId === user)

  private def invitationOf(user: Long, id: Long) = tables
    .invitations
    .filter(invite => invite.id === id && invite.userId === user)

  /** Locks groups' rows in ascending order (see [[GroupStore]]). */
  private def lock(groups: Seq[Long]): DBIO[Seq[GroupRow]] =
    ifAny(groups)(Seq.empty): held =>
      tables.locked(tables.groups.filter(_.id inSet held).sortBy(_.id)).result

  private def lockOwnedBy(owner: Long): DBIO[Seq[GroupRow]] = tables
    .locked(ownedBy(owner).sortBy(_.id))
    .result

  /** Hands the change the rows read under the groups' locks. */
  private def holding[X]
    (groups: Seq[Long])
    (change: Seq[GroupRow] => DBIO[X])
    : IO[X] = db.run(lock(groups).flatMap(change).transactionally)

  /** The group is missing to anyone but its owner. */
  private def managing[X](owner: Long, group: Long)(change: DBIO[X]): IO[X] =
    holding(
      Seq(group),
    )(held => ensureOwner(held, owner, group).flatMap(_ => change))

  private def owns(owner: Long, id: Long): DBIO[Boolean] = ownedBy(owner)
    .filter(_.id === id)
    .exists
    .result

  private def ensureOwner
    (
      held: Seq[GroupRow],
      owner: Long,
      group: Long,
    )
    : DBIO[Unit] = refuseUnless(
    GroupStore.ownedAmong(held, owner, group),
    AuthRefusal.GroupMissing,
  )

  private def ensureParent
    (
      held: Seq[GroupRow],
      owner: Long,
      parent: Option[Long],
    )
    : DBIO[Unit] = refuseUnless(
    parent.forall(GroupStore.ownedAmong(held, owner, _)),
    AuthRefusal.ParentGroupMissing,
  )

  private def ensureAcyclic
    (
      owner: Long,
      id: Long,
      parent: Option[Long],
    )
    : DBIO[Unit] = parent.fold[DBIO[Unit]](DBIO.unit)(target =>
    ownedBy(owner)
      .result
      .flatMap(groups =>
        refuseUnless(
          !GroupStore.subtree(groups, id).exists(_.contains(target)),
          AuthRefusal.GroupInsideItself,
        ),
      ),
  )

  /**
    * Invites a user unless they are a member or invitee, or enrols them if they
    * are the owner or an applicant. Callers must hold the group's lock.
    */
  private def offer(owner: Long, group: Long, user: Long): DBIO[Unit] =
    if user == owner then enrol(group, user)
    else
      memberOf(group, user)
        .exists
        .result
        .zip(invitationTo(group, user).exists.result)
        .zip(requestFrom(group, user).exists.result)
        .flatMap:
          case ((false, false), false) =>
            (tables.invitations += InvitationRow(0, group, user)).unit
          case ((false, _), true) => enrol(group, user)
          case _                  => DBIO.unit

  /**
    * Records a request to join a group the user can see, unless they are a
    * member or asked already. Callers must hold the group's lock.
    */
  private def ask(group: GroupRow, user: Long): DBIO[Unit] = maySee(user, group)
    .flatMap(refuseUnless(_, AuthRefusal.GroupMissing))
    .flatMap(_ =>
      insertUnless(
        memberOf(group.id, user).exists || requestFrom(group.id, user).exists,
      )(tables.requests += RequestRow(group.id, user)),
    )

  /**
    * Whether a group is public, or the user is a member of it or of a group it
    * is nested inside.
    */
  private def maySee(user: Long, group: GroupRow): DBIO[Boolean] =
    if group.public then DBIO.successful(true)
    else
      for
        direct  <- directOf(user)
        parents <- forest(Seq(group.ownerId))
      yield GroupStore
        .withAncestors(Seq(group.id), parents)
        .exists(direct.map(_._1).toSet)

  /**
    * Enrols a user, settling any invitation or request that brought them.
    * Callers must hold the group's lock.
    */
  private def enrol(group: Long, user: Long): DBIO[Unit] =
    for
      _ <- invitationTo(group, user).delete
      _ <- requestFrom(group, user).delete
      _ <- addMember(group, user)
    yield ()

  private def addMember(group: Long, user: Long): DBIO[Unit] = insertUnless(
    memberOf(group, user).exists,
  )(tables.members += MemberRow(group, user))

  /** Callers must hold the group's lock, or the check may not hold. */
  private def insertUnless(found: Rep[Boolean])(insert: DBIO[Any]): DBIO[Unit] =
    found.result.flatMap(present => if present then DBIO.unit else insert.unit)

object GroupStore:

  /** The default lookup of resources, locking nothing and finding each. */
  val everywhere: Resource => DBIO[Option[String]] =
    resource => DBIO.successful(Some(resource.kind))

  /** A group and its descendants among the owner's groups, or else nothing. */
  private def owned(groups: Seq[GroupRow], id: Long): Seq[Long] =
    subtree(groups, id).getOrElse(Seq.empty)

  /**
    * Who sees anything of some groups, and what their members belong to through
    * them.
    *
    * @param owners
    *   The owners of the groups.
    *
    * @param members
    *   The direct members of the groups.
    *
    * @param people
    *   Everyone who sees anything of the groups: owners, direct members,
    *   invitees, applicants, and the members of every enclosing group.
    *
    * @param public
    *   Whether any of the groups is public.
    *
    * @param enclosing
    *   The groups, with every group enclosing them.
    */
  private[server] final case class Surroundings
    (
      owners: Set[Long],
      members: Set[Long],
      people: Set[Long],
      public: Boolean,
      enclosing: Set[Long],
    ):

    /** Whom a change to the groups' members concerns. */
    def membersChanged(person: Long): Affected = Affected.Groups(
      Audience.People(owners ++ members + person),
      enclosing,
    )

  private def byGroup(pairs: Seq[(Long, UserRow)]): Map[Long, List[User]] =
    pairs
      .groupMap(_._1)(_._2.toUser)
      .view
      .mapValues(_.sortBy(_.username).toList)
      .toMap

  /** The group and every group beneath it, or `None` if the forest lacks it. */
  private def subtree(groups: Seq[GroupRow], root: Long): Option[Seq[Long]] =
    Option.when(groups.exists(_.id == root))(
      withDescendants(
        Seq(root),
        groups.map(g => (g.id, g.parentId)),
      ).toSeq,
    )

  private def ownedAmong
    (
      rows: Seq[GroupRow],
      owner: Long,
      group: Long,
    )
    : Boolean = rows.exists(row => row.id == group && row.ownerId == owner)

  /** The groups with every ancestor, never climbing into a group twice. */
  private def withAncestors
    (
      direct: Seq[Long],
      parents: Map[Long, Option[Long]],
    )
    : List[Long] =
    def climb(id: Long, seen: Set[Long]): Set[Long] =
      if seen(id) then seen
      else parents.get(id).flatten.fold(seen + id)(climb(_, seen + id))
    direct
      .foldLeft(Set.empty[Long])((seen, id) => climb(id, seen))
      .toList
      .sorted

  /** The groups with every descendant, never descending into a group twice. */
  private def withDescendants
    (
      roots: Seq[Long],
      parents: Iterable[(Long, Option[Long])],
    )
    : Set[Long] =
    val children = parents.groupMap(_._2)(_._1)
    def descend(id: Long, seen: Set[Long]): Set[Long] =
      if seen(id) then seen
      else
        children
          .getOrElse(Some(id), Nil)
          .foldLeft(seen + id)((seen, child) => descend(child, seen))
    roots.foldLeft(Set.empty[Long])((seen, root) => descend(root, seen))
