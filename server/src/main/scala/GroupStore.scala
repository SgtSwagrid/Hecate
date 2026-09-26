package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{
  AuthRefusal, Group, GroupDraft, GroupView, Invitation, Joinable, LinkTarget,
  Membership, Principal, User,
}
import slick.dbio.DBIO
import slick.jdbc.JdbcCapabilities

/**
  * The store of user groups, their memberships, and invitations to join them.
  * Groups are managed only by the user who created them. The members of a group
  * see it, its manager and one another ([[memberships]]), but never who is
  * invited to it or asking to join, which is its manager's business alone.
  *
  * No group may ever contain itself, however deeply: [[update]] refuses any
  * move that would nest a group inside its own subtree, and [[create]] cannot
  * form a cycle, as a new group has nothing inside it yet. Every walk of the
  * tree here is guarded all the same, and never enters the same group twice, so
  * that a cycle arriving by some other route could not spin forever.
  *
  * Nobody joins a group without consenting, and nobody joins one without its
  * owner's agreement either. The owner may join at once. Anyone else joins when
  * both sides have agreed, whichever agreed first: by accepting the owner's
  * invitation, by having their own request to join admitted, or by following an
  * invite link the owner made. Until then the group reaches them in no way at
  * all. Declining deletes the invitation or the request, and either side may
  * ask again: neither confers anything, so at worst it is a question the other
  * can decline again.
  *
  * A request may only be made of a group its asker can see: a public one, or
  * one nested inside a group they are a member of. Any other group is reported
  * as missing, as it would be to anyone who is not its owner.
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
    * The grants of the groups this store deletes, which go with them. Held here
    * rather than built for each deletion, so that what this store depends on is
    * named once and in one place.
    */
  private val grants = GrantStore(tables, db)

  /** The invite links of the groups this store deletes, which go with them. */
  private val links = LinkStore(tables)

  /**
    * Lists every group owned by the given user, with its direct members and the
    * users invited to it. The groups they are merely a member of are
    * [[memberships]], which is a different question.
    */
  def owned(owner: Long): IO[List[GroupView]] = db.run:
    for
      groups <- groupsOf(owner).sortBy(_.id).result
      ids = groups.map(_.id)
      enrolled <- enrolledIn(ids)
      invitees <- ifAny(ids)(Seq.empty)(shown =>
        tables
          .invitations
          .filter(_.groupId inSet shown)
          .join(tables.users)
          .on(_.userId === _.id)
          .result,
      )
      applicants <- ifAny(ids)(Seq.empty)(shown =>
        tables
          .requests
          .filter(_.groupId inSet shown)
          .join(tables.users)
          .on(_.userId === _.id)
          .result,
      )
      codes <- links.toGroups(ids)
      invited =
        GroupStore.byGroup(invitees.map((row, user) => (row.groupId, user)))
      asking =
        GroupStore.byGroup(applicants.map((row, user) => (row.groupId, user)))
    yield groups
      .map(group =>
        GroupView(
          group.toGroup,
          enrolled.getOrElse(group.id, List.empty),
          invited.getOrElse(group.id, List.empty),
          asking.getOrElse(group.id, List.empty),
          codes.get(group.id),
        ),
      )
      .toList

  /**
    * The groups the given user is directly a member of, whoever owns them, each
    * with its owner and everyone directly enrolled in it, as the members of a
    * group see one another.
    *
    * Only direct memberships are listed. The enclosing groups reached by
    * [[groupIdsOf]] are deliberately left out, as a user cannot leave one they
    * never joined; and so, therefore, are their members.
    */
  def memberships(user: Long): IO[List[Membership]] = db.run:
    for
      joined <- tables
        .members
        .filter(_.userId === user)
        .join(tables.groups)
        .on(_.groupId === _.id)
        .join(tables.users)
        .on(_._2.owner === _.id)
        .map((row, owner) => (row._2, owner))
        .sortBy(_._1.id)
        .result
      enrolled <- enrolledIn(joined.map(_._1.id))
    yield joined
      .map((group, owner) =>
        Membership(
          group.toGroup,
          owner.toUser,
          enrolled.getOrElse(group.id, List.empty),
        ),
      )
      .toList

  /**
    * The users directly enrolled in each of the given groups, sorted by
    * username. A group with nobody in it is absent. Never asks the database
    * about no groups.
    */
  private def enrolledIn(groups: Seq[Long]): DBIO[Map[Long, List[User]]] =
    ifAny(groups)(Map.empty): shown =>
      tables
        .members
        .filter(_.groupId inSet shown)
        .join(tables.users)
        .on(_.userId === _.id)
        .result
        .map(found =>
          GroupStore.byGroup(found.map((row, user) => (row.groupId, user))),
        )

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
    * The groups the given user may ask to join, each with its owner and whether
    * they have asked already: every public group, and every group nested inside
    * one they are a member of, however deeply, but none they own, belong to or
    * are invited to, since they may join those already. Any group they have
    * asked to join is included whether or not they could still find it, so that
    * they can always withdraw the request. A group's parent is withheld unless
    * it is a group they can see.
    */
  def joinable(user: Long): IO[List[Joinable]] = db.run:
    for
      direct  <- directOf(user)
      parents <- forest(direct.map(_._2))
      joined = direct.map(_._1).toSet
      nested = GroupStore.withDescendants(joined.toSeq, parents.toSeq).toSet
      asked   <- tables.requests.filter(_.userId === user).map(_.groupId).result
      invited <- tables
        .invitations
        .filter(_.userId === user)
        .map(_.groupId)
        .result
      found <- findable(nested ++ asked)
        .filterNot(_.owner === user)
        .join(tables.users)
        .on(_.owner === _.id)
        .sortBy(_._1.name)
        .result
      offered = found.filterNot((group, _) =>
        joined(group.id) || invited.contains(group.id),
      )
      seen = joined ++ nested ++ offered.map(_._1.id)
    yield offered
      .map((group, owner) =>
        Joinable(
          group.toGroup.copy(parent = group.parent.filter(seen)),
          owner.toUser,
          asked.contains(group.id),
        ),
      )
      .toList

  /** The query for every public group, and every one of the given groups. */
  private def findable(groups: Set[Long]) = tables
    .groups
    .filter(group =>
      if groups.isEmpty then group.public
      else group.public || (group.id inSet groups),
    )

  /**
    * The groups the given user is directly a member of, each with its owner, as
    * the owner's forest is where any group nested inside them lies.
    */
  private def directOf(user: Long): DBIO[Seq[(Long, Long)]] = tables
    .members
    .filter(_.userId === user)
    .join(tables.groups)
    .on(_.groupId === _.id)
    .map(row => (row._1.groupId, row._2.owner))
    .result

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
    *
    * Every permission check a host makes begins here, so it reads as little as
    * it can: only the groups of the owners whose groups the user is in, since
    * an ancestor is always a group of the same owner, and nothing at all for a
    * user who is in no group.
    */
  def groupIdsOf(user: Long): IO[List[Long]] = db.run:
    for
      direct  <- directOf(user)
      parents <- forest(direct.map(_._2))
    yield GroupStore.withAncestors(direct.map(_._1), parents)

  /**
    * Every group of the given owners, as a map from each to its parent: the
    * whole of the forest that any ancestor or descendant of theirs can lie in,
    * since [[create]] and [[update]] let a group be nested only inside a group
    * of the same owner. Never asks the database about no owners.
    *
    * A group whose parent is somebody else's, which no path through this store
    * can produce, is simply where a walk stops: it confers less access rather
    * than more.
    */
  private def forest(owners: Seq[Long]): DBIO[Map[Long, Option[Long]]] =
    val distinct = owners.distinct
    if distinct.isEmpty then DBIO.successful(Map.empty)
    else
      tables
        .groups
        .filter(_.owner inSet distinct)
        .map(group => (group.id, group.parent))
        .result
        .map(_.toMap)

  /**
    * The identifiers of every user who is a member of any of the given groups,
    * or of any group nested inside one however deeply: everyone that anything
    * addressed to those groups reaches. The mirror of [[groupIdsOf]], which
    * walks the tree upwards from a user, and guarded against cycles in the same
    * way. Never asks the database about no groups.
    */
  def membersWithin(groups: Seq[Long]): IO[List[Long]] =
    if groups.isEmpty then IO.pure(List.empty)
    else
      db.run:
        for
          parents <- tables.groups.map(group => (group.id, group.parent)).result
          within = GroupStore.withDescendants(groups, parents)
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
    _ <- ensure(
      owns(owner, id),
      AuthRefusal.GroupMissing,
    )
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
      else DBIO.failed(AuthProblem(AuthRefusal.GroupMissing))
    _ <- deleteGroups(GroupStore.subtree(groups, id).toSeq)
  yield ()).transactionally)

  /**
    * Invites the user with the given username to one group, returning them.
    * Inviting someone already a member, or already invited, changes nothing.
    * Inviting someone who has asked to join admits them, and an owner inviting
    * themselves joins, as either way both sides have now agreed.
    *
    * Naming a user who does not exist is reported as such, which does let a
    * caller test whether a username is registered. That is a product decision,
    * and deliberately unlike [[AuthService]]'s sign-in, which goes to some
    * length not to disclose the same fact: someone inviting a list of names
    * needs to know which of them were typed wrongly.
    */
  def invite(owner: Long, group: Long, username: String): IO[User] = db.run((for
    _ <- lock(Seq(group))
    _ <- ensure(
      owns(owner, group),
      AuthRefusal.GroupMissing,
    )
    found <- tables.users.filter(_.username === username).result.headOption
    user  <- required(
      found,
      AuthRefusal.NoSuchUser(username),
    )
    _ <- offer(owner, group, user.id)
  yield user.toUser).transactionally)

  /**
    * Accepts one of the given user's invitations, making them a member of its
    * group.
    */
  def accept(user: Long, invitation: Long): IO[Unit] = db.run((for
    found <- invitationOf(user, invitation).result.headOption
    row   <- required(found, AuthRefusal.InvitationMissing)
    _     <- lock(Seq(row.groupId))
    // Re-checked under the lock: the invitation may have been accepted,
    // declined, or its group deleted, while this waited. Deleting it, rather
    // than reading it again, both checks and claims it in one step.
    taken <- invitationOf(user, invitation).delete
    _     <-
      if taken == 1 then enrol(row.groupId, user)
      else DBIO.failed(AuthProblem(AuthRefusal.InvitationMissing))
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
        else DBIO.failed(AuthProblem(AuthRefusal.InvitationMissing)),
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
    * Makes the owner of one group a member of it, as whoever manages a group
    * needs nobody's leave to join it. Does nothing when they are one already.
    */
  def join(owner: Long, group: Long): IO[Unit] = db.run((for
    _ <- lock(Seq(group))
    _ <- ensure(
      owns(owner, group),
      AuthRefusal.GroupMissing,
    )
    _ <- enrol(group, owner)
  yield ()).transactionally)

  /**
    * Asks, on the given user's behalf, to join one group they can see, for its
    * owner to admit or decline. Asking twice, or as a member, changes nothing.
    * Asking to join a group they are invited to accepts the invitation, and
    * asking to join one they own joins it, as either way both sides have now
    * agreed. A group they cannot see is reported as missing.
    */
  def request(user: Long, group: Long): IO[Unit] = db.run((for
    _     <- lock(Seq(group))
    found <- tables.groups.filter(_.id === group).result.headOption
    row   <- required(found, AuthRefusal.GroupMissing)
    ready <-
      if row.owner == user then DBIO.successful(true)
      else invitationTo(group, user).exists.result
    _ <- if ready then enrol(group, user) else ask(row, user)
  yield ()).transactionally)

  /**
    * Withdraws the given user's request to join one group. Does nothing when
    * they had not asked.
    */
  def retract(user: Long, group: Long): IO[Unit] = db.run((for
    _ <- lock(Seq(group))
    _ <- requestFrom(group, user).delete
  yield ()).transactionally)

  /**
    * Admits to one group, at its owner's request, a user who has asked to join
    * it. Nobody who has not asked can be admitted, so that nobody joins without
    * consenting; admitting a member changes nothing.
    */
  def admit(owner: Long, group: Long, user: Long): IO[Unit] = db.run(
    (
      for
        _ <- lock(Seq(group))
        _ <- ensure(
          owns(owner, group),
          AuthRefusal.GroupMissing,
        )
        present <- memberOf(group, user).exists.result
        asked   <- requestFrom(group, user).exists.result
        _       <-
          if present then DBIO.successful(())
          else if asked then enrol(group, user)
          else DBIO.failed(AuthProblem(AuthRefusal.RequestMissing))
      yield ()
    ).transactionally,
  )

  /**
    * Makes one group public, so that everyone can find it and ask to join, or
    * private again, so that only the members of the groups it is nested inside
    * can. Requests already made stand either way, for the owner to answer.
    */
  def publish(owner: Long, group: Long, public: Boolean): IO[Unit] = db.run((for
    _ <- ensure(
      owns(owner, group),
      AuthRefusal.GroupMissing,
    )
    _ <- tables.groups.filter(_.id === group).map(_.public).update(public)
  yield ()).transactionally)

  /**
    * Gives one group an invite link unless it has one, yielding the link's code
    * either way. Whoever follows the link joins the group: see [[follow]].
    */
  def link(owner: Long, group: Long): IO[String] =
    linking(owner, group)(links.ensure(owner, LinkTarget.Joining(group)))

  /**
    * Replaces one group's invite link with one of a new code, yielding it, so
    * that the old link stops working.
    */
  def relink(owner: Long, group: Long): IO[String] =
    linking(owner, group)(links.renew(owner, LinkTarget.Joining(group)))

  /** Turns off one group's invite link, if it has one. */
  def unlink(owner: Long, group: Long): IO[Unit] =
    linking(owner, group)(links.remove(LinkTarget.Joining(group)))

  /**
    * Changes one group's invite link at its owner's request, under the lock
    * that keeps a group to one link.
    */
  private def linking[X](owner: Long, group: Long)(change: DBIO[X]): IO[X] = db
    .run((for
      _ <- lock(Seq(group))
      _ <- ensure(
        owns(owner, group),
        AuthRefusal.GroupMissing,
      )
      result <- change
    yield result).transactionally)

  /**
    * Makes the given user a member of the group an invite link leads to,
    * provided the link still does once the group is locked: its owner may have
    * replaced or turned it off meanwhile. Composes into the caller's
    * transaction.
    *
    * user The identifier of the user following the link.
    *
    * group The identifier of the group the link was found to lead to.
    *
    * code The code of the link.
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

  /**
    * The name of one group, and whether the given user is a member of it, for
    * showing someone where an invite link leads; `None` if there is no such
    * group.
    */
  private[server] def glance
    (user: Long, group: Long)
    : DBIO[Option[(String, Boolean)]] = tables
    .groups
    .filter(_.id === group)
    .map(row => (row.name, memberOf(group, user).exists))
    .result
    .headOption

  /**
    * Who sees anything of the given groups as they now stand, and what the
    * people in them belong to through them. Groups that no longer exist concern
    * nobody, so a group about to be deleted must be asked about beforehand.
    */
  private[server] def surroundings
    (groups: Seq[Long])
    : IO[GroupStore.Surroundings] = db.run:
    for
      rows <- ifAny(groups.distinct)(Seq.empty[GroupRow])(ids =>
        tables.groups.filter(_.id inSet ids).result,
      )
      ids = rows.map(_.id)
      parents <- forest(rows.map(_.owner))
      enclosing = GroupStore.withAncestors(ids, parents)
      enrolled <- ifAny(enclosing)(Seq.empty[(Long, Long)])(within =>
        tables
          .members
          .filter(_.groupId inSet within)
          .map(member => (member.groupId, member.userId))
          .result,
      )
      invitees <- ifAny(ids)(Seq.empty[Long])(within =>
        tables.invitations.filter(_.groupId inSet within).map(_.userId).result,
      )
      applicants <- ifAny(ids)(Seq.empty[Long])(within =>
        tables.requests.filter(_.groupId inSet within).map(_.userId).result,
      )
    yield GroupStore.Surroundings(
      rows.map(_.owner).toSet,
      enrolled
        .collect { case (group, user) if ids.contains(group) => user }
        .toSet,
      rows.map(_.owner).toSet ++ enrolled.map(_._2) ++ invitees ++ applicants,
      rows.exists(_.public),
      enclosing.toSet,
    )

  /**
    * The given group and every group nested beneath it, provided the given user
    * owns it: everything deleting it would delete. Empty for anyone else.
    */
  private[server] def subtreeOf(owner: Long, group: Long): IO[Seq[Long]] = db
    .run(groupsOf(owner).result)
    .map(groups =>
      if groups.exists(_.id == group) then
        GroupStore.subtree(groups, group).toSeq
      else Seq.empty,
    )

  /** The group one of the given user's invitations is to, if they have it. */
  private[server] def invitedTo(user: Long, invitation: Long): IO[Seq[Long]] =
    db.run(invitationOf(user, invitation).map(_.groupId).result)

  /**
    * Removes one user from one group at its owner's request, whether a member,
    * an invitee, whose invitation is then cancelled, or an applicant, whose
    * request is then declined.
    */
  def withdraw(owner: Long, group: Long, user: Long): IO[Unit] = db.run((for
    _ <- lock(Seq(group))
    _ <- ensure(
      owns(owner, group),
      AuthRefusal.GroupMissing,
    )
    _ <- memberOf(group, user).delete
    _ <- invitationTo(group, user).delete
    _ <- requestFrom(group, user).delete
  yield ()).transactionally)

  /**
    * Deletes the given groups, which the caller must already hold locked, with
    * their memberships, invitations, requests, invite links and grants, and the
    * host's rows attached to them. Composes into the caller's transaction.
    */
  private def deleteGroups(doomed: Seq[Long]): DBIO[Unit] =
    ifAny(doomed)(()): gone =>
      val principals = gone.map(Principal.Group(_))
      for
        _ <- tables.members.filter(_.groupId inSet gone).delete
        _ <- tables.invitations.filter(_.groupId inSet gone).delete
        _ <- tables.requests.filter(_.groupId inSet gone).delete
        _ <- links.removeFromGroups(gone)
        _ <- grants.revokeHeldBy(principals)
        _ <- cascade(principals)
        _ <- tables.groups.filter(_.id inSet gone).delete
      yield ()

  /**
    * Removes every trace of one user from the groups: deletes the groups they
    * own, exactly as [[delete]] would, their memberships of, invitations to and
    * requests to join everyone else's, and every invite link they made, to a
    * group or to a resource, as nobody would be left to answer for it. Composes
    * into an account's deletion.
    */
  private[server] def forget(user: Long): DBIO[Unit] =
    for
      owned <- locked(groupsOf(user).sortBy(_.id)).result
      _     <- deleteGroups(owned.map(_.id))
      _     <- tables.members.filter(_.userId === user).delete
      _     <- tables.invitations.filter(_.userId === user).delete
      _     <- tables.requests.filter(_.userId === user).delete
      _     <- links.removeMadeBy(user)
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
      case Principal.Group(id) => db.run(owns(user, id))

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

  /** The query for the request of one user to join one group. */
  private def requestFrom(group: Long, user: Long) = tables
    .requests
    .filter(asking => asking.groupId === group && asking.userId === user)

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
  private def lock(groups: Seq[Long]): DBIO[Unit] = ifAny(groups)(()): held =>
    locked(tables.groups.filter(_.id inSet held).sortBy(_.id)).result.unit

  /** Whether the given user owns a group with the given identifier. */
  private def owns(owner: Long, id: Long): DBIO[Boolean] = groupsOf(owner)
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
          owns(owner, id),
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
    * Invites a user to a group at its owner's request, unless they are a member
    * or have already been invited; or makes them a member, if they are the
    * owner or have asked to join, since then both sides have agreed. Callers
    * must hold the group's lock, which is what makes this check-then-insert
    * safe: see [[GroupStore]].
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
          case _                  => DBIO.successful(())

  /**
    * Records a user's request to join a group, unless they are a member or have
    * asked already, provided they can see it. Callers must hold the group's
    * lock, as for [[offer]].
    */
  private def ask(group: GroupRow, user: Long): DBIO[Unit] = ensure(
    canSee(user, group),
    AuthRefusal.GroupMissing,
  ).flatMap(_ =>
    memberOf(group.id, user)
      .exists
      .result
      .zip(requestFrom(group.id, user).exists.result)
      .flatMap:
        case (false, false) =>
          (tables.requests += RequestRow(group.id, user)).unit
        case _ => DBIO.successful(()),
  )

  /**
    * Whether the given user can see a group they do not own: it is public, or
    * they are a member of it or of a group it is nested inside, however deeply.
    * Every such group is the owner's, so only the owner's forest is walked.
    */
  private def canSee(user: Long, group: GroupRow): DBIO[Boolean] =
    if group.public then DBIO.successful(true)
    else
      for
        direct  <- directOf(user)
        parents <- forest(Seq(group.owner))
      yield GroupStore
        .withAncestors(Seq(group.id), parents)
        .exists(direct.map(_._1).contains)

  /**
    * Makes a user a member of a group, unless they already are, and settles
    * whatever invitation or request brought them there. Callers must hold the
    * group's lock.
    */
  private def enrol(group: Long, user: Long): DBIO[Unit] =
    for
      _ <- invitationTo(group, user).delete
      _ <- requestFrom(group, user).delete
      _ <- ensureMember(group, user)
    yield ()

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
      else (tables.members += MemberRow(group, user)).unit,
    )

object GroupStore:

  /**
    * Who sees anything of some groups, and what the people in them belong to
    * through them.
    *
    * @param owners
    *   The owners of the groups.
    *
    * @param members
    *   The direct members of the groups, who see one another.
    *
    * @param people
    *   Everyone who sees anything of the groups: their owners; their direct
    *   members, invitees and applicants; and the direct members of every group
    *   enclosing them, who may find them and ask to join.
    *
    * @param public
    *   Whether any of the groups is public, and so seen by anyone at all.
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
    )

  /**
    * The users linked to each group, sorted by username, given the links of
    * every group at once: grouped here once, rather than the whole list being
    * searched through again for every group.
    */
  private def byGroup(links: Seq[(Long, UserRow)]): Map[Long, List[User]] =
    links
      .groupMap(_._1)(_._2.toUser)
      .view
      .mapValues(_.sortBy(_.username).toList)
      .toMap

  /**
    * The identifiers of the given group and every group nested beneath it,
    * within one owner's forest of groups.
    */
  private def subtree(groups: Seq[GroupRow], root: Long): Set[Long] = GroupStore
    .withDescendants(
      Seq(root),
      groups.map(g => (g.id, g.parent)),
    )
    .toSet

  /**
    * The given group identifiers together with every ancestor of theirs,
    * walking `parents` and never climbing into the same group twice.
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
    * The given groups together with every group nested inside them, given every
    * group's parent. The index from a group to its children is built once,
    * however many groups are walked, and no group is descended into twice, so a
    * cycle could not spin forever.
    */
  private def withDescendants
    (
      roots: Seq[Long],
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
    roots
      .foldLeft(Set.empty[Long])((acc, root) => descend(root, acc))
      .toList
      .sorted
