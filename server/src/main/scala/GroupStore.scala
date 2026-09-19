package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{
  AuthRefusal, Group, GroupDraft, GroupView, Invitation, Principal, User,
}
import slick.dbio.DBIO
import slick.jdbc.JdbcCapabilities

/**
  * The store of user groups, their memberships, and invitations to join them.
  * Groups are only ever visible to, and managed by, the user who created them.
  *
  * Nobody joins a group without consenting. An owner can only invite; the
  * invited user becomes a member when they accept, and until then the group
  * reaches them in no way at all. Declining deletes the invitation, and the
  * owner may invite them again: an invitation confers nothing, so at worst it
  * is a request the user can decline again.
  *
  * Every change to who is in or invited to a group first locks the group's own
  * row, and holds it until the transaction ends. The tables cannot declare the
  * uniqueness constraints that would otherwise keep a user from being invited
  * or enrolled twice (see [[AuthTables]]), so each change instead checks before
  * it writes; and under the usual `READ COMMITTED` isolation, two such changes
  * running at once would both see nothing there and both write. The group row
  * is the one row every such change agrees on, so the second waits for the
  * first to commit, then sees what it wrote. Any new change of this kind must
  * take the same lock. Where several groups are locked at once, they are locked
  * in one statement in ascending order of identifier, so that two transactions
  * can never each hold a row the other is waiting for. On a database without
  * `SELECT … FOR UPDATE` the lock is skipped; SQLite, the one such database
  * Slick supports, lets only one writer in at a time anyway.
  *
  * @param tables
  *   The tables the groups, memberships and invitations are stored in.
  *
  * @param db
  *   The database to run the queries against.
  *
  * @param cascade
  *   Deletes whatever the host application attaches to the given principals,
  *   which are about to be deleted: the groups of a deleted subtree. Run inside
  *   the same transaction, and before the groups themselves are removed, so
  *   that an application's own rows never outlive the groups they point at. The
  *   grants the groups held are revoked by this store itself.
  */
final class GroupStore
  (
    tables: AuthTables,
    db: Transactor,
    cascade: Seq[Principal] => DBIO[Unit] = _ => DBIO.successful(()),
  ):

  import tables.profile.api.*

  /** Whether the database can lock the rows a query reads. */
  private val canLock = tables
    .profile
    .capabilities
    .contains(JdbcCapabilities.forUpdate)

  /**
    * Lists every group owned by the given user, with its direct members and the
    * users invited to it.
    */
  def list(owner: Long): IO[List[GroupView]] = db.run:
    for
      groups <- groupsOf(owner).sortBy(_.id).result
      ids = groups.map(_.id)
      members <- tables
        .members
        .filter(_.groupId inSet ids)
        .join(tables.users)
        .on(_.userId === _.id)
        .result
      invitees <- tables
        .invitations
        .filter(_.groupId inSet ids)
        .join(tables.users)
        .on(_.userId === _.id)
        .result
    yield groups
      .map(group =>
        GroupView(
          group.toGroup,
          GroupStore.usersOf(
            group.id,
            members.map((row, user) => (row.groupId, user)),
          ),
          GroupStore.usersOf(
            group.id,
            invitees.map((row, user) => (row.groupId, user)),
          ),
        ),
      )
      .toList

  /**
    * The groups the given user is directly a member of, whoever owns them.
    *
    * Only direct memberships are listed. The enclosing groups reached by
    * [[groupIdsOf]] are deliberately left out, as a user cannot leave one they
    * never joined.
    */
  def memberships(user: Long): IO[List[Group]] = db
    .run(
      tables
        .members
        .filter(_.userId === user)
        .join(tables.groups)
        .on(_.groupId === _.id)
        .map(_._2)
        .sortBy(_.id)
        .result,
    )
    .map(_.map(_.toGroup).toList)

  /**
    * The pending invitations sent to the given user, each naming the group and
    * who sent it. A group's place in its owner's hierarchy is withheld, as it
    * is none of the invitee's business.
    */
  def invitations(user: Long): IO[List[Invitation]] = db
    .run(
      tables
        .invitations
        .filter(_.userId === user)
        .join(tables.groups)
        .on(_.groupId === _.id)
        .join(tables.users)
        .on(_._2.owner === _.id)
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
    * The identifiers of every group the given user effectively belongs to: the
    * groups they are a member of, plus every ancestor of those, so that
    * anything addressed to an enclosing group also reaches the members of its
    * nested subgroups. Invitations count for nothing here, as an invited user
    * has not consented to anything.
    *
    * Only memberships of groups that still exist are counted. The locking
    * described on [[GroupStore]] should never leave a membership behind its
    * group, but this is what a host application acts on, so it does not rely on
    * that alone.
    */
  def groupIdsOf(user: Long): IO[List[Long]] = db.run:
    for
      direct <- tables
        .members
        .filter(_.userId === user)
        .join(tables.groups)
        .on(_.groupId === _.id)
        .map(_._1.groupId)
        .result
      parents <- tables.groups.map(group => (group.id, group.parent)).result
    yield GroupStore.withAncestors(direct, parents.toMap)

  /**
    * The identifiers of every user who is a member of any of the given groups,
    * or of any group nested inside one however deeply: everyone that anything
    * addressed to those groups reaches. The mirror of [[groupIdsOf]], which
    * walks the tree upwards from a user, and tolerant of cycles in the same
    * way. Never asks the database about no groups.
    */
  def membersWithin(groups: Seq[Long]): IO[List[Long]] =
    if groups.isEmpty then IO.pure(List.empty)
    else
      db.run:
        for
          parents <- tables.groups.map(group => (group.id, group.parent)).result
          within = groups
            .flatMap(GroupStore.withDescendants(_, parents))
            .distinct
          members <- tables
            .members
            .filter(_.groupId inSet within)
            .map(_.userId)
            .distinct
            .sortBy(identity)
            .result
        yield members.toList

  /**
    * The identifiers of every user who may address the given user: the owners
    * of the groups they are a member of. Whoever gave them something, or shared
    * something with them, goes on reaching them while they remain in one of
    * that person's groups.
    */
  def addressersOf(user: Long): IO[Set[Long]] = db
    .run(
      tables
        .members
        .filter(_.userId === user)
        .join(tables.groups)
        .on(_.groupId === _.id)
        .map(_._2.owner)
        .distinct
        .result,
    )
    .map(_.toSet)

  /** Stores a new group for the given owner, nested under any given parent. */
  def create(owner: Long, draft: GroupDraft): IO[Group] = db.run((for
    // The parent is locked so that it cannot be deleted from under its new
    // child, leaving the child in no tree at all.
    _  <- lock(draft.parent.toSeq)
    _  <- ensureParent(owner, draft.parent)
    id <- tables.groups.returning(tables.groups.map(_.id)) +=
      GroupRow(0, owner, draft.name, draft.parent)
  yield Group(id, draft.name, draft.parent)).transactionally)

  /**
    * Renames and/or moves one stored group. A move that would nest a group
    * inside itself is refused.
    */
  def update(owner: Long, id: Long, draft: GroupDraft): IO[Unit] = db.run((for
    _ <- lock(id +: draft.parent.toSeq)
    _ <- ensure(owned(owner, id), GroupStore.missing)
    _ <- ensureParent(owner, draft.parent)
    _ <- ensureAcyclic(owner, id, draft.parent)
    _ <- tables
      .groups
      .filter(_.id === id)
      .map(group => (group.name, group.parent))
      .update((draft.name, draft.parent))
  yield ()).transactionally)

  /**
    * Deletes one stored group together with every group nested beneath it,
    * their memberships, invitations and grants, and whatever the host
    * application attaches to them.
    */
  def delete(owner: Long, id: Long): IO[Unit] = db.run((for
    // Every group of the owner is locked, and read under that lock, so the
    // subtree found here includes any group nested beneath it concurrently.
    groups <- locked(groupsOf(owner).sortBy(_.id)).result
    _      <-
      if groups.exists(_.id == id) then DBIO.successful(())
      else DBIO.failed(AuthProblem(GroupStore.missing))
    _ <- deleteGroups(GroupStore.subtree(groups, id).toSeq)
  yield ()).transactionally)

  /**
    * Invites the user with the given username to one group, returning them.
    * Inviting someone already a member, or already invited, changes nothing.
    *
    * Naming a user who does not exist is reported as such, which does let a
    * caller test whether a username is registered. That is a product decision,
    * and deliberately unlike [[AuthService]]'s sign-in, which goes to some
    * length not to disclose the same fact: someone inviting a list of names
    * needs to know which of them were typed wrongly.
    */
  def invite(owner: Long, group: Long, username: String): IO[User] = db.run((for
    _     <- lock(Seq(group))
    _     <- ensure(owned(owner, group), GroupStore.missing)
    found <- tables.users.filter(_.username === username).result.headOption
    user  <- required(
      found,
      AuthRefusal.NoSuchUser(username),
    )
    _ <- offer(group, user.id)
  yield user.toUser).transactionally)

  /**
    * Accepts one of the given user's invitations, making them a member of its
    * group.
    */
  def accept(user: Long, invitation: Long): IO[Unit] = db.run((for
    found <- invitationOf(user, invitation).result.headOption
    row   <- required(found, GroupStore.noInvitation)
    _     <- lock(Seq(row.groupId))
    // Re-checked under the lock: the invitation may have been accepted,
    // declined, or its group deleted, while this waited. Deleting it, rather
    // than reading it again, both checks and claims it in one step.
    taken <- invitationOf(user, invitation).delete
    _     <-
      if taken == 1 then ensureMember(row.groupId, user)
      else DBIO.failed(AuthProblem(GroupStore.noInvitation))
  yield ()).transactionally)

  /**
    * Declines one of the given user's invitations, deleting it. A single
    * statement, so the invitation's own row lock suffices against a racing
    * acceptance: whichever deletes it first wins, and the other finds nothing.
    */
  def decline(user: Long, invitation: Long): IO[Unit] = db.run(
    invitationOf(user, invitation)
      .delete
      .flatMap(removed =>
        if removed == 1 then DBIO.successful(())
        else DBIO.failed(AuthProblem(GroupStore.noInvitation)),
      ),
  )

  /**
    * Withdraws the given user from one group at their own request. Does nothing
    * when they were not a member.
    */
  def leave(user: Long, group: Long): IO[Unit] = db.run((for
    _ <- lock(Seq(group))
    _ <- memberOf(group, user).delete
  yield ()).transactionally)

  /**
    * Removes one user from one group at its owner's request, whether a member
    * or an invitee, whose invitation is then cancelled.
    */
  def withdraw(owner: Long, group: Long, user: Long): IO[Unit] = db.run((for
    _ <- lock(Seq(group))
    _ <- ensure(owned(owner, group), GroupStore.missing)
    _ <- memberOf(group, user).delete
    _ <- invitationTo(group, user).delete
  yield ()).transactionally)

  /**
    * Deletes the given groups, which the caller must already hold locked, with
    * their memberships, invitations and grants, and the host's rows attached to
    * them. Composes into the caller's transaction.
    */
  private def deleteGroups(doomed: Seq[Long]): DBIO[Unit] =
    val principals = doomed.map(Principal.Group(_))
    if doomed.isEmpty then DBIO.successful(())
    else
      for
        _ <- tables.members.filter(_.groupId inSet doomed).delete
        _ <- tables.invitations.filter(_.groupId inSet doomed).delete
        _ <- GrantStore(tables, db).revokeHeldBy(principals)
        _ <- cascade(principals)
        _ <- tables.groups.filter(_.id inSet doomed).delete
      yield ()

  /**
    * Removes every trace of one user from the groups: deletes the groups they
    * own, exactly as [[delete]] would, and their memberships of and invitations
    * to everyone else's. Composes into an account's deletion.
    */
  private[server] def forget(user: Long): DBIO[Unit] =
    for
      owned <- locked(groupsOf(user).sortBy(_.id)).result
      _     <- deleteGroups(owned.map(_.id))
      _     <- tables.members.filter(_.userId === user).delete
      _     <- tables.invitations.filter(_.userId === user).delete
    yield ()

  /**
    * The principals the given user may address, for a host application's
    * pickers: themselves, every group they own, and every member of those
    * groups. Nested groups are included, as a group can only ever be nested
    * inside a group of the same owner.
    *
    * Invitees are excluded. Addressing someone gives them something (a
    * document, access), so the relation must be one they consented to and can
    * end: an accepted membership, which they may leave, rather than an
    * invitation they may never have seen.
    */
  def addressable(user: Long): IO[List[Principal]] = db.run:
    for
      owned   <- groupsOf(user).sortBy(_.id).map(_.id).result
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
    * Whether the given user may address the given principal, as [[addressable]]
    * would list it, answered in one query rather than by listing everyone they
    * may address.
    */
  def mayAddress(user: Long, principal: Principal): IO[Boolean] =
    principal match
      case Principal.Person(id) if id == user => IO.pure(true)
      case Principal.Person(id)               => db.run(
          tables
            .members
            .filter(_.userId === id)
            .join(tables.groups)
            .on(_.groupId === _.id)
            .filter(_._2.owner === user)
            .exists
            .result,
        )
      case Principal.Group(id) => db.run(owned(user, id))

  /** The query for every group owned by the given user. */
  private def groupsOf(owner: Long) = tables.groups.filter(_.owner === owner)

  /** The query for one user's membership of one group. */
  private def memberOf(group: Long, user: Long) = tables
    .members
    .filter(member => member.groupId === group && member.userId === user)

  /** The query for the invitation of one user to one group. */
  private def invitationTo(group: Long, user: Long) = tables
    .invitations
    .filter(invite => invite.groupId === group && invite.userId === user)

  /** The query for one invitation, provided it was sent to the given user. */
  private def invitationOf(user: Long, id: Long) = tables
    .invitations
    .filter(invite => invite.id === id && invite.userId === user)

  /**
    * The given query of groups, locking the rows it reads until the transaction
    * ends, where the database can.
    */
  private def locked[E, U](query: Query[E, U, Seq]): Query[E, U, Seq] =
    if canLock then query.forUpdate else query

  /**
    * Locks the given groups' rows until the transaction ends, in ascending
    * order of identifier, so that changes to who is in or invited to them
    * happen one at a time. See [[GroupStore]].
    */
  private def lock(groups: Seq[Long]): DBIO[Unit] =
    if groups.isEmpty then DBIO.successful(())
    else
      locked(tables.groups.filter(_.id inSet groups).sortBy(_.id))
        .result
        .map(_ => ())

  /** Whether the given user owns a group with the given identifier. */
  private def owned(owner: Long, id: Long): DBIO[Boolean] = groupsOf(owner)
    .filter(_.id === id)
    .exists
    .result

  /** Fails the transaction with the given message unless the check holds. */
  private def ensure
    (
      check: DBIO[Boolean],
      problem: AuthRefusal,
    )
    : DBIO[Unit] = check.flatMap(ok =>
    if ok then DBIO.successful(()) else DBIO.failed(AuthProblem(problem)),
  )

  /** The found row, or a failed transaction with the given message. */
  private def required[X](found: Option[X], problem: AuthRefusal): DBIO[X] =
    found.fold[DBIO[X]](DBIO.failed(AuthProblem(problem)))(DBIO.successful)

  /** Fails the transaction unless any given parent is owned by the user. */
  private def ensureParent(owner: Long, parent: Option[Long]): DBIO[Unit] =
    parent match
      case None     => DBIO.successful(())
      case Some(id) => ensure(
          owned(owner, id),
          AuthRefusal.ParentGroupMissing,
        )

  /** Fails the transaction when moving under the parent would form a cycle. */
  private def ensureAcyclic
    (
      owner: Long,
      id: Long,
      parent: Option[Long],
    )
    : DBIO[Unit] = parent match
    case None         => DBIO.successful(())
    case Some(target) => groupsOf(owner)
        .result
        .flatMap(groups =>
          if GroupStore.subtree(groups, id).contains(target) then
            DBIO.failed(AuthProblem(AuthRefusal.GroupInsideItself))
          else DBIO.successful(()),
        )

  /**
    * Invites a user to a group, unless they are a member or have already been
    * invited. Callers must hold the group's lock, which is what makes this
    * check-then-insert safe: see [[GroupStore]].
    */
  private def offer(group: Long, user: Long): DBIO[Unit] = memberOf(group, user)
    .exists
    .result
    .zip(invitationTo(group, user).exists.result)
    .flatMap:
      case (false, false) =>
        (tables.invitations += InvitationRow(0, group, user)).map(_ => ())
      case _ => DBIO.successful(())

  /**
    * Makes a user a member of a group, unless they already are. Callers must
    * hold the group's lock.
    */
  private def ensureMember(group: Long, user: Long): DBIO[Unit] = memberOf(
    group,
    user,
  ).exists
    .result
    .flatMap(present =>
      if present then DBIO.successful(())
      else (tables.members += MemberRow(group, user)).map(_ => ()),
    )

object GroupStore:

  /**
    * The refusal for a group that does not exist, or is not the user's. One
    * refusal for both, so that neither can be told from the other.
    */
  private val missing = AuthRefusal.GroupMissing

  /**
    * The refusal for an invitation that does not exist, or was sent to somebody
    * else. One refusal for both, as for [[missing]].
    */
  private val noInvitation = AuthRefusal.InvitationMissing

  /** The users linked to one group, sorted by username. */
  private def usersOf(group: Long, links: Seq[(Long, UserRow)]): List[User] =
    links
      .collect { case (id, user) if id == group => user.toUser }
      .sortBy(_.username)
      .toList

  /**
    * The identifiers of the given group and every group nested beneath it,
    * within one owner's forest of groups.
    */
  private def subtree(groups: Seq[GroupRow], root: Long): Set[Long] = GroupStore
    .withDescendants(root, groups.map(g => (g.id, g.parent)))
    .toSet

  /**
    * The given group identifiers together with every ancestor of theirs,
    * walking `parents` and tolerating any cycle it may contain.
    */
  private def withAncestors
    (
      direct: Seq[Long],
      parents: Map[Long, Option[Long]],
    )
    : List[Long] =
    def climb(id: Long, seen: Set[Long]): Set[Long] =
      if seen(id) then seen
      else parents.get(id).flatten.fold(seen + id)(climb(_, seen + id))
    direct.foldLeft(Set.empty[Long])((acc, id) => climb(id, acc)).toList.sorted

  /**
    * One group together with every group nested inside it, given every group's
    * parent, tolerating cycles.
    */
  private def withDescendants
    (
      root: Long,
      parents: Seq[(Long, Option[Long])],
    )
    : List[Long] =
    val children = parents.groupMap(_._2)(_._1)
    def descend(id: Long, seen: Set[Long]): Set[Long] =
      if seen(id) then seen
      else
        children
          .getOrElse(Some(id), Nil)
          .foldLeft(seen + id)((acc, child) => descend(child, acc))
    descend(root, Set.empty).toList.sorted
