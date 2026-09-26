package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  GroupDraft, Invitation, InviteCode, LinkTarget, User,
}
import munit.CatsEffectSuite

/**
  * Tests of the ways into a group besides an invitation: an owner joining their
  * own group, a request to join and its answer, a public group, and a group's
  * invite link.
  */
class JoiningSuite extends CatsEffectSuite:

  /** Runs a check against stores backed by a fresh in-memory database. */
  private def withStores
    (name: String)
    (check: (GroupStore, UserStore, TestDb) => IO[Unit])
    : IO[Unit] = TestDb
    .open(s"joining-$name")
    .use(db =>
      check(
        GroupStore(TestDb.tables, db),
        UserStore(TestDb.tables, db),
        db,
      ),
    )

  /** Registers a user for tests. */
  private def newUser(users: UserStore, name: String): IO[User] = users
    .register(name, "hash")
    .map(_.get)

  /** Invites a user to a group, and has them accept. */
  private def enrol
    (
      groups: GroupStore,
      owner: User,
      group: Long,
      member: User,
    )
    : IO[Unit] =
    for
      _    <- groups.invite(owner.id, group, member.username)
      sent <- groups.invitations(member.id)
      _    <- groups.accept(
        member.id,
        sent.find(_.group.id == group).get.id,
      )
    yield ()

  /** The identifiers of the direct members of one of the owner's groups. */
  private def membersOf
    (
      groups: GroupStore,
      owner: User,
      group: Long,
    )
    : IO[List[Long]] = groups
    .owned(owner.id)
    .map(_.find(_.group.id == group).toList.flatMap(_.members.map(_.id)))

  /** The message a failed action was refused with. */
  private def refusal(action: IO[?]): IO[Option[String]] = action
    .attempt
    .map(_.left.toOption.map(_.getMessage))

  private val english = Wording.english

  test("an owner joins their own group directly, and only their own"):
    withStores("join"): (groups, users, _) =>
      for
        owner    <- newUser(users, "owner")
        stranger <- newUser(users, "stranger")
        team     <- groups.create(owner.id, GroupDraft("Team"))
        _        <- groups.join(owner.id, team.id)
        _        <- groups.join(owner.id, team.id)
        refused  <- refusal(groups.join(stranger.id, team.id))
        members  <- membersOf(groups, owner, team.id)
      yield
        assertEquals(members, List(owner.id))
        assertEquals(refused, Some(english.groupMissing))

  test("an owner inviting themselves joins rather than being invited"):
    withStores("self-invite"): (groups, users, _) =>
      for
        owner   <- newUser(users, "owner")
        team    <- groups.create(owner.id, GroupDraft("Team"))
        _       <- groups.invite(owner.id, team.id, owner.username)
        pending <- groups.invitations(owner.id)
        members <- membersOf(groups, owner, team.id)
      yield
        assertEquals(pending, List.empty[Invitation])
        assertEquals(members, List(owner.id))

  test("a member of a group may ask to join a group nested inside it"):
    withStores("nested-request"): (groups, users, _) =>
      for
        owner  <- newUser(users, "owner")
        member <- newUser(users, "member")
        dept   <- groups.create(owner.id, GroupDraft("Department"))
        team   <- groups.create(
          owner.id,
          GroupDraft("Team", Some(dept.id)),
        )
        _     <- enrol(groups, owner, dept.id, member)
        _     <- groups.request(member.id, team.id)
        _     <- groups.request(member.id, team.id)
        views <- groups.owned(owner.id)
        found <- groups.joinable(member.id)
      yield
        assertEquals(
          views.find(_.group.id == team.id).map(_.applicants.map(_.id)),
          Some(List(member.id)),
        )
        assertEquals(
          found.map(offer => (offer.group.id, offer.requested)),
          List(team.id -> true),
        )
        assertEquals(
          found.map(_.manager.id),
          List(owner.id),
        )

  test("admitting someone who asked makes them a member"):
    withStores("admit"): (groups, users, _) =>
      for
        owner  <- newUser(users, "owner")
        member <- newUser(users, "member")
        team   <- groups.create(owner.id, GroupDraft("Team"))
        _      <- groups.publish(owner.id, team.id, true)
        _      <- groups.request(member.id, team.id)
        _      <- groups.admit(owner.id, team.id, member.id)
        views  <- groups.owned(owner.id)
        found  <- groups.joinable(member.id)
      yield
        val view = views.find(_.group.id == team.id).get
        assertEquals(
          view.members.map(_.id),
          List(member.id),
        )
        assertEquals(view.applicants, List.empty[User])
        assertEquals(found, List.empty)

  test("nobody can be admitted who has not asked"):
    withStores("admit-unasked"): (groups, users, _) =>
      for
        owner    <- newUser(users, "owner")
        stranger <- newUser(users, "stranger")
        team     <- groups.create(owner.id, GroupDraft("Team"))
        refused  <- refusal(groups.admit(owner.id, team.id, stranger.id))
        members  <- membersOf(groups, owner, team.id)
      yield
        assertEquals(refused, Some(english.requestMissing))
        assertEquals(members, List.empty)

  test(
    "a private group cannot be asked to join by a stranger, a public one can",
  ):
    withStores("public"): (groups, users, _) =>
      for
        owner    <- newUser(users, "owner")
        stranger <- newUser(users, "stranger")
        team     <- groups.create(owner.id, GroupDraft("Team"))
        before   <- groups.joinable(stranger.id)
        refused  <- refusal(groups.request(stranger.id, team.id))
        _        <- groups.publish(owner.id, team.id, true)
        after    <- groups.joinable(stranger.id)
        _        <- groups.request(stranger.id, team.id)
        views    <- groups.owned(owner.id)
      yield
        assertEquals(before, List.empty)
        assertEquals(refused, Some(english.groupMissing))
        assertEquals(after.map(_.group.id), List(team.id))
        assertEquals(after.map(_.group.public), List(true))
        assertEquals(
          views.flatMap(_.applicants.map(_.id)),
          List(stranger.id),
        )

  test("only the owner may make a group public"):
    withStores("publish-owner"): (groups, users, _) =>
      for
        owner    <- newUser(users, "owner")
        stranger <- newUser(users, "stranger")
        team     <- groups.create(owner.id, GroupDraft("Team"))
        refused  <- refusal(groups.publish(stranger.id, team.id, true))
        found    <- groups.joinable(stranger.id)
      yield
        assertEquals(refused, Some(english.groupMissing))
        assertEquals(found, List.empty)

  test("a public group nested in a private one hides its parent"):
    withStores("hidden-parent"): (groups, users, _) =>
      for
        owner    <- newUser(users, "owner")
        stranger <- newUser(users, "stranger")
        dept     <- groups.create(owner.id, GroupDraft("Department"))
        team     <- groups.create(
          owner.id,
          GroupDraft("Team", Some(dept.id)),
        )
        _     <- groups.publish(owner.id, team.id, true)
        found <- groups.joinable(stranger.id)
      yield assertEquals(found.map(_.group.parent), List(None))

  test("groups the user owns, belongs to or is invited to are not joinable"):
    withStores("not-joinable"): (groups, users, _) =>
      for
        owner  <- newUser(users, "owner")
        member <- newUser(users, "member")
        mine   <- groups.create(member.id, GroupDraft("Mine"))
        joined <- groups.create(owner.id, GroupDraft("Joined"))
        asked  <- groups.create(owner.id, GroupDraft("Invited"))
        _      <- groups.publish(member.id, mine.id, true)
        _      <- groups.publish(owner.id, joined.id, true)
        _      <- groups.publish(owner.id, asked.id, true)
        _      <- enrol(groups, owner, joined.id, member)
        _      <- groups.invite(owner.id, asked.id, member.username)
        found  <- groups.joinable(member.id)
      yield assertEquals(found, List.empty)

  test("asking to join a group one is invited to accepts the invitation"):
    withStores("ask-invited"): (groups, users, _) =>
      for
        owner   <- newUser(users, "owner")
        member  <- newUser(users, "member")
        team    <- groups.create(owner.id, GroupDraft("Team"))
        _       <- groups.invite(owner.id, team.id, member.username)
        _       <- groups.request(member.id, team.id)
        pending <- groups.invitations(member.id)
        members <- membersOf(groups, owner, team.id)
      yield
        assertEquals(pending, List.empty[Invitation])
        assertEquals(members, List(member.id))

  test("inviting someone who has asked to join admits them"):
    withStores("invite-asked"): (groups, users, _) =>
      for
        owner   <- newUser(users, "owner")
        member  <- newUser(users, "member")
        team    <- groups.create(owner.id, GroupDraft("Team"))
        _       <- groups.publish(owner.id, team.id, true)
        _       <- groups.request(member.id, team.id)
        _       <- groups.invite(owner.id, team.id, member.username)
        pending <- groups.invitations(member.id)
        members <- membersOf(groups, owner, team.id)
      yield
        assertEquals(pending, List.empty[Invitation])
        assertEquals(members, List(member.id))

  test("a request withdrawn or declined is gone, and may be made again"):
    withStores("retract"): (groups, users, _) =>
      for
        owner    <- newUser(users, "owner")
        member   <- newUser(users, "member")
        team     <- groups.create(owner.id, GroupDraft("Team"))
        _        <- groups.publish(owner.id, team.id, true)
        _        <- groups.request(member.id, team.id)
        _        <- groups.retract(member.id, team.id)
        withdrew <- groups.owned(owner.id)
        _        <- groups.request(member.id, team.id)
        _        <- groups.withdraw(owner.id, team.id, member.id)
        declined <- groups.owned(owner.id)
        again    <- groups.joinable(member.id)
      yield
        assertEquals(
          withdrew.flatMap(_.applicants),
          List.empty[User],
        )
        assertEquals(
          declined.flatMap(_.applicants),
          List.empty[User],
        )
        assertEquals(again.map(_.requested), List(false))

  test(
    "a group's invite link is made once, replaced on request, and turned off",
  ):
    withStores("group-link"): (groups, users, _) =>
      for
        owner   <- newUser(users, "owner")
        team    <- groups.create(owner.id, GroupDraft("Team"))
        first   <- groups.link(owner.id, team.id)
        same    <- groups.link(owner.id, team.id)
        shown   <- groups.owned(owner.id)
        renewed <- groups.relink(owner.id, team.id)
        _       <- groups.unlink(owner.id, team.id)
        gone    <- groups.owned(owner.id)
      yield
        assertEquals(same, first)
        assertEquals(InviteCode.parse(first), Some(first))
        assertEquals(
          shown.map(_.inviteCode),
          List(Some(first)),
        )
        assertNotEquals(renewed, first)
        assertEquals(gone.map(_.inviteCode), List(None))

  test("only the owner may make a group's invite link"):
    withStores("group-link-owner"): (groups, users, _) =>
      for
        owner    <- newUser(users, "owner")
        stranger <- newUser(users, "stranger")
        team     <- groups.create(owner.id, GroupDraft("Team"))
        refused  <- refusal(groups.link(stranger.id, team.id))
      yield assertEquals(refused, Some(english.groupMissing))

  test("deleting a group turns off its invite link and ends its requests"):
    withStores("delete-link"): (groups, users, db) =>
      val links = LinkStore(TestDb.tables)
      for
        owner  <- newUser(users, "owner")
        member <- newUser(users, "member")
        team   <- groups.create(owner.id, GroupDraft("Team"))
        _      <- groups.publish(owner.id, team.id, true)
        _      <- groups.request(member.id, team.id)
        code   <- groups.link(owner.id, team.id)
        _      <- groups.delete(owner.id, team.id)
        found  <- db.run(links.find(code))
        asked  <- groups.joinable(member.id)
      yield
        assertEquals(found, None)
        assertEquals(asked, List.empty)

  test("a link leads where it was made to, whatever case its code is typed in"):
    withStores("link-case"): (groups, users, db) =>
      val links = LinkStore(TestDb.tables)
      for
        owner <- newUser(users, "owner")
        team  <- groups.create(owner.id, GroupDraft("Team"))
        code  <- groups.link(owner.id, team.id)
        found <- db.run(links.find(code.toUpperCase))
      yield
        assertEquals(
          found.flatMap(_._1.target),
          Some(LinkTarget.Joining(team.id)),
        )
        assertEquals(found.map(_._2.id), Some(owner.id))
