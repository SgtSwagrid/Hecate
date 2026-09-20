package com.alecdorrington.hecate
package server

// The query syntax of the very profile the tables under test are built on.
// Importing a concrete profile's `api` instead would put a second, unrelated
// set of the same extension methods in scope, which resolve against neither
// these tables nor each other.
import TestDb.tables.profile.api.*
import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.hecate.model.{
  Access, Grant, GroupDraft, Invitation, Principal, Resource, User,
}
import munit.CatsEffectSuite
import scala.concurrent.ExecutionContext
import slick.jdbc.{H2Profile, JdbcCapabilities, SQLiteProfile}

class GroupStoreSuite extends CatsEffectSuite:

  /** Runs the cascade hook's query combinators inline, as the stores do. */
  private given ExecutionContext = ExecutionContext.parasitic

  /** Runs a check against stores backed by a fresh in-memory database. */
  private def withStores
    (name: String)
    (check: (GroupStore, UserStore) => IO[Unit])
    : IO[Unit] = TestDb.groups(s"groups-$name").use(check.tupled)

  /** Registers a user for tests, yielding their assigned identifier. */
  private def newUser(users: UserStore, name: String): IO[User] = users
    .register(name, "hash")
    .map(_.get)

  /** The invitation the given user holds to the given group. */
  private def invitationTo
    (
      groups: GroupStore,
      user: User,
      group: Long,
    )
    : IO[Invitation] = groups
    .invitations(user.id)
    .map(_.find(_.group.id == group).get)

  /** Invites a user to a group, and has them accept. */
  private def join
    (
      groups: GroupStore,
      owner: User,
      group: Long,
      member: User,
    )
    : IO[Unit] =
    for
      _          <- groups.invite(owner.id, group, member.username)
      invitation <- invitationTo(groups, member, group)
      _          <- groups.accept(member.id, invitation.id)
    yield ()

  /** The message a failed action was refused with. */
  private def refusal(action: IO[?]): IO[Option[String]] = action
    .attempt
    .map(_.left.toOption.map(_.getMessage))

  test("a created group is listed back for its owner, initially empty"):
    withStores("create"): (groups, users) =>
      for
        owner <- newUser(users, "manager")
        made  <- groups.create(owner.id, GroupDraft("Sales"))
        all   <- groups.list(owner.id)
      yield
        assertEquals(all.map(_.group), List(made))
        assertEquals(all.flatMap(_.members), List.empty)
        assertEquals(all.flatMap(_.invitees), List.empty)

  test("groups are only visible to their owner"):
    withStores("scoped"): (groups, users) =>
      for
        alice <- newUser(users, "alice")
        bob   <- newUser(users, "bob")
        _     <- groups.create(alice.id, GroupDraft("Sales"))
        seen  <- groups.list(bob.id)
      yield assertEquals(seen, List.empty)

  test("groups nest to arbitrary depth via their parent"):
    withStores("nested"): (groups, users) =>
      for
        owner <- newUser(users, "manager")
        dept  <- groups.create(owner.id, GroupDraft("Sales"))
        team  <- groups.create(
          owner.id,
          GroupDraft("Retail", Some(dept.id)),
        )
        _ <- groups.create(
          owner.id,
          GroupDraft("Front desk", Some(team.id)),
        )
        all <- groups.list(owner.id)
      yield assertEquals(
        all.map(view => (view.group.name, view.group.parent)),
        List(
          ("Sales", None),
          ("Retail", Some(dept.id)),
          ("Front desk", Some(team.id)),
        ),
      )

  test("another owner's group cannot be used as a parent"):
    withStores("foreign-parent"): (groups, users) =>
      for
        alice   <- newUser(users, "alice")
        bob     <- newUser(users, "bob")
        theirs  <- groups.create(alice.id, GroupDraft("Sales"))
        attempt <- groups
          .create(
            bob.id,
            GroupDraft("Retail", Some(theirs.id)),
          )
          .attempt
      yield assert(attempt.isLeft)

  test("a group cannot be moved inside its own subtree"):
    withStores("cycle"): (groups, users) =>
      for
        owner <- newUser(users, "manager")
        dept  <- groups.create(owner.id, GroupDraft("Sales"))
        team  <- groups.create(
          owner.id,
          GroupDraft("Retail", Some(dept.id)),
        )
        onto <- groups
          .update(
            owner.id,
            dept.id,
            GroupDraft("Sales", Some(team.id)),
          )
          .attempt
        ontoSelf <- groups
          .update(
            owner.id,
            dept.id,
            GroupDraft("Sales", Some(dept.id)),
          )
          .attempt
      yield
        assert(onto.isLeft)
        assert(ontoSelf.isLeft)

  test("updating renames and moves a group"):
    withStores("update"): (groups, users) =>
      for
        owner <- newUser(users, "manager")
        dept  <- groups.create(owner.id, GroupDraft("Sales"))
        team  <- groups.create(owner.id, GroupDraft("Retail"))
        _     <- groups.update(
          owner.id,
          team.id,
          GroupDraft("10B", Some(dept.id)),
        )
        all <- groups.list(owner.id)
      yield assertEquals(
        all.map(view => (view.group.name, view.group.parent)),
        List(("Sales", None), ("10B", Some(dept.id))),
      )

  test("deleting a group deletes its subtree, memberships and invitations"):
    withStores("delete"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        invited  <- newUser(users, "invited")
        dept     <- groups.create(owner.id, GroupDraft("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDraft("Retail", Some(dept.id)),
        )
        keep    <- groups.create(owner.id, GroupDraft("Chess club"))
        _       <- join(groups, owner, team.id, employee)
        _       <- groups.invite(owner.id, team.id, "invited")
        _       <- groups.delete(owner.id, dept.id)
        all     <- groups.list(owner.id)
        theirs  <- groups.groupIdsOf(employee.id)
        pending <- groups.invitations(invited.id)
      yield
        assertEquals(all.map(_.group), List(keep))
        assertEquals(theirs, List.empty)
        assertEquals(pending, List.empty)

  test("another owner's group cannot be deleted"):
    withStores("foreign-delete"): (groups, users) =>
      for
        alice   <- newUser(users, "alice")
        bob     <- newUser(users, "bob")
        theirs  <- groups.create(alice.id, GroupDraft("Sales"))
        attempt <- groups.delete(bob.id, theirs.id).attempt
        all     <- groups.list(alice.id)
      yield
        assert(attempt.isLeft)
        assertEquals(all.map(_.group), List(theirs))

  test("the deletion cascade sees the whole subtree, before it is deleted"):
    TestDb
      .open("groups-cascade")
      .use: db =>
        // Recorded by the hook as the deletion runs, then read back afterwards.
        var cascaded  = List.empty[Long]
        var surviving = 0
        val groups    = GroupStore(
          TestDb.tables,
          db,
          principals =>
            val ids = principals.collect { case Principal.Group(id) => id }
            // The groups are still there while the cascade runs, so that a host
            // application is free to join against them.
            TestDb
              .tables
              .groups
              .filter(_.id inSet ids)
              .length
              .result
              .map: present =>
                cascaded = ids.toList
                surviving = present,
        )
        val users = UserStore(TestDb.tables, db)
        for
          owner <- newUser(users, "manager")
          dept  <- groups.create(owner.id, GroupDraft("Sales"))
          team  <- groups.create(
            owner.id,
            GroupDraft("Retail", Some(dept.id)),
          )
          _ <- groups.create(owner.id, GroupDraft("Chess club"))
          _ <- groups.delete(owner.id, dept.id)
        yield
          assertEquals(
            cascaded.sorted,
            List(dept.id, team.id).sorted,
          )
          assertEquals(surviving, 2)

  test(
    "an invited user is an invitee, not a member, and is reached by nothing",
  ):
    withStores("invite"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        team     <- groups.create(owner.id, GroupDraft("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        view     <- groups.list(owner.id).map(_.head)
        sent     <- groups.invitations(employee.id)
        reached  <- groups.groupIdsOf(employee.id)
        joined   <- groups.memberships(employee.id)
      yield
        assertEquals(view.members, List.empty)
        assertEquals(view.invitees, List(employee))
        assertEquals(sent.map(_.group.id), List(team.id))
        assertEquals(sent.map(_.inviter), List(owner))
        assertEquals(reached, List.empty)
        assertEquals(joined, List.empty)

  test("accepting an invitation makes a member, and the group reaches them"):
    withStores("accept"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        dept     <- groups.create(owner.id, GroupDraft("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDraft("Retail", Some(dept.id)),
        )
        _       <- join(groups, owner, team.id, employee)
        view    <- groups.list(owner.id).map(_.find(_.group.id == team.id).get)
        sent    <- groups.invitations(employee.id)
        reached <- groups.groupIdsOf(employee.id)
      yield
        assertEquals(view.members, List(employee))
        assertEquals(view.invitees, List.empty)
        assertEquals(sent, List.empty)
        assertEquals(reached, List(dept.id, team.id).sorted)

  test("an invitation can be accepted only once, and only by its invitee"):
    withStores("accept-once"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        stranger <- newUser(users, "stranger")
        team     <- groups.create(owner.id, GroupDraft("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        sent     <- invitationTo(groups, employee, team.id)
        stolen   <- refusal(groups.accept(stranger.id, sent.id))
        _        <- groups.accept(employee.id, sent.id)
        again    <- refusal(groups.accept(employee.id, sent.id))
        members  <- groups.list(owner.id).map(_.flatMap(_.members))
      yield
        assertEquals(
          stolen,
          Some("That invitation doesn't exist."),
        )
        assertEquals(
          again,
          Some("That invitation doesn't exist."),
        )
        assertEquals(members, List(employee))

  test("inviting the same user twice makes one invitation"):
    withStores("invite-twice"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        team     <- groups.create(owner.id, GroupDraft("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        _        <- groups.invite(owner.id, team.id, "employee")
        sent     <- groups.invitations(employee.id)
      yield assertEquals(sent.size, 1)

  test("inviting a member changes nothing"):
    withStores("invite-member"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        team     <- groups.create(owner.id, GroupDraft("Retail"))
        _        <- join(groups, owner, team.id, employee)
        _        <- groups.invite(owner.id, team.id, "employee")
        view     <- groups.list(owner.id).map(_.head)
      yield
        assertEquals(view.members, List(employee))
        assertEquals(view.invitees, List.empty)

  test("declining deletes the invitation, and the user may be invited again"):
    withStores("decline"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        team     <- groups.create(owner.id, GroupDraft("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        sent     <- invitationTo(groups, employee, team.id)
        _        <- groups.decline(employee.id, sent.id)
        declined <- groups.list(owner.id).map(_.head.invitees)
        held     <- groups.invitations(employee.id)
        _        <- groups.invite(owner.id, team.id, "employee")
        again    <- groups.list(owner.id).map(_.head.invitees)
      yield
        assertEquals(declined, List.empty)
        assertEquals(held, List.empty)
        assertEquals(again, List(employee))

  test("a declined invitation can no longer be accepted"):
    withStores("decline-accept"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        team     <- groups.create(owner.id, GroupDraft("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        sent     <- invitationTo(groups, employee, team.id)
        _        <- groups.decline(employee.id, sent.id)
        late     <- refusal(groups.accept(employee.id, sent.id))
        members  <- groups.list(owner.id).map(_.head.members)
      yield
        assertEquals(
          late,
          Some("That invitation doesn't exist."),
        )
        assertEquals(members, List.empty)

  test("leaving ends the membership, and the user may be invited again"):
    withStores("leave"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        team     <- groups.create(owner.id, GroupDraft("Retail"))
        _        <- join(groups, owner, team.id, employee)
        _        <- groups.leave(employee.id, team.id)
        joined   <- groups.memberships(employee.id)
        reached  <- groups.groupIdsOf(employee.id)
        _        <- groups.invite(owner.id, team.id, "employee")
        view     <- groups.list(owner.id).map(_.head)
      yield
        assertEquals(joined, List.empty)
        assertEquals(reached, List.empty)
        assertEquals(view.members, List.empty)
        assertEquals(view.invitees, List(employee))

  test("deleting a group revokes the grants it and its subgroups held"):
    TestDb
      .open("groups-grants")
      .use: db =>
        val groups   = GroupStore(TestDb.tables, db)
        val users    = UserStore(TestDb.tables, db)
        val grants   = GrantStore(TestDb.tables, db)
        val document = Resource("document", 1)
        for
          owner <- newUser(users, "manager")
          dept  <- groups.create(owner.id, GroupDraft("Sales"))
          team  <- groups.create(
            owner.id,
            GroupDraft("Retail", Some(dept.id)),
          )
          keep <- groups.create(owner.id, GroupDraft("Chess club"))
          _    <- db.run(grants.grant(Grant(
            document,
            Principal.Group(team.id),
            Access.View,
          )))
          _ <- db.run(grants.grant(Grant(
            document,
            Principal.Group(keep.id),
            Access.View,
          )))
          _    <- groups.delete(owner.id, dept.id)
          left <- grants.grantsOver(document)
        yield assertEquals(
          left.map(_.principal),
          List(Principal.Group(keep.id)),
        )

  test("an owner's removal is no refusal, so the user can be invited again"):
    withStores("withdraw"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        team     <- groups.create(owner.id, GroupDraft("Retail"))
        _        <- join(groups, owner, team.id, employee)
        _        <- groups.withdraw(owner.id, team.id, employee.id)
        removed  <- groups.list(owner.id).map(_.head)
        _        <- groups.invite(owner.id, team.id, "employee")
        again    <- groups.list(owner.id).map(_.head.invitees)
      yield
        assertEquals(removed.members, List.empty)
        assertEquals(removed.invitees, List.empty)
        assertEquals(again, List(employee))

  test("an owner can cancel a pending invitation"):
    withStores("cancel"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        team     <- groups.create(owner.id, GroupDraft("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        _        <- groups.withdraw(owner.id, team.id, employee.id)
        view     <- groups.list(owner.id).map(_.head)
        sent     <- groups.invitations(employee.id)
      yield
        assertEquals(view.invitees, List.empty)
        assertEquals(sent, List.empty)

  test("an unknown username cannot be invited"):
    withStores("invite-unknown"): (groups, users) =>
      for
        owner   <- newUser(users, "manager")
        team    <- groups.create(owner.id, GroupDraft("Retail"))
        refused <- refusal(groups.invite(owner.id, team.id, "nobody"))
      yield assertEquals(
        refused,
        Some("No user is called \"nobody\"."),
      )

  test("inviting into another's group reads like a group that does not exist"):
    withStores("invite-foreign"): (groups, users) =>
      for
        alice   <- newUser(users, "alice")
        bob     <- newUser(users, "bob")
        _       <- newUser(users, "employee")
        theirs  <- groups.create(alice.id, GroupDraft("Sales"))
        foreign <- refusal(groups.invite(bob.id, theirs.id, "employee"))
        absent  <- refusal(groups.invite(bob.id, 9999L, "employee"))
      yield
        assertEquals(
          foreign,
          Some("That group doesn't exist."),
        )
        assertEquals(foreign, absent)

  test("a member's effective groups include every ancestor"):
    withStores("ancestors"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        dept     <- groups.create(owner.id, GroupDraft("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDraft("Retail", Some(dept.id)),
        )
        row <- groups.create(
          owner.id,
          GroupDraft("Front desk", Some(team.id)),
        )
        _      <- groups.create(owner.id, GroupDraft("Chess club"))
        _      <- join(groups, owner, row.id, employee)
        theirs <- groups.groupIdsOf(employee.id)
      yield assertEquals(
        theirs,
        List(dept.id, team.id, row.id).sorted,
      )

  test("a membership of a group that no longer exists reaches nothing"):
    TestDb
      .open("groups-orphan")
      .use: db =>
        val groups = GroupStore(TestDb.tables, db)
        val users  = UserStore(TestDb.tables, db)
        for
          owner    <- newUser(users, "manager")
          employee <- newUser(users, "employee")
          team     <- groups.create(owner.id, GroupDraft("Retail"))
          _        <- join(groups, owner, team.id, employee)
          // Deleted behind the store's back, as a host writing rows directly
          // might, so that the membership is left pointing at nothing.
          _ <- db.run(TestDb.tables.groups.filter(_.id === team.id).delete)
          reached <- groups.groupIdsOf(employee.id)
        yield assertEquals(reached, List.empty)

  test("a user can see the groups they belong to"):
    withStores("memberships"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        dept     <- groups.create(owner.id, GroupDraft("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDraft("Retail", Some(dept.id)),
        )
        _    <- groups.create(owner.id, GroupDraft("Chess club"))
        _    <- join(groups, owner, team.id, employee)
        mine <- groups.memberships(employee.id)
      // Only the group they actually joined: an enclosing group is not one
      // they could leave.
      yield assertEquals(mine, List(team))

  test("leaving a group one is not in changes nothing"):
    withStores("leave-absent"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        employee <- newUser(users, "employee")
        other    <- newUser(users, "other")
        team     <- groups.create(owner.id, GroupDraft("Retail"))
        _        <- join(groups, owner, team.id, employee)
        _        <- groups.leave(other.id, team.id)
        view     <- groups.list(owner.id).map(_.head)
      yield
        assertEquals(view.members, List(employee))
        assertEquals(view.invitees, List.empty)

  /**
    * [[GroupStore]] takes its row locks only where the profile declares them,
    * because a profile that lacks them may not omit `FOR UPDATE` but emit it
    * and fail at runtime. Pins the one database each way, so that the guard is
    * not mistaken for dead code while every test here runs on H2.
    */
  test("row locks are taken on H2, and skipped on SQLite, which has none"):
    assert(H2Profile.capabilities.contains(JdbcCapabilities.forUpdate))
    assert(!SQLiteProfile.capabilities.contains(JdbcCapabilities.forUpdate))

  test(
    "the owners of a member's groups may address them, and reach them within",
  ):
    withStores("addressers"): (groups, users) =>
      for
        alice <- newUser(users, "alice")
        bob   <- newUser(users, "bob")
        carol <- newUser(users, "carol")
        dept  <- groups.create(alice.id, GroupDraft("Sales"))
        team  <- groups.create(
          alice.id,
          GroupDraft("Retail", Some(dept.id)),
        )
        _    <- groups.invite(alice.id, team.id, "bob")
        sent <- groups.invitations(bob.id)
        _    <- groups.accept(bob.id, sent.head.id)
        _    <- groups.addressersOf(bob.id).assertEquals(Set(alice.id))
        _    <- groups.addressersOf(carol.id).assertEquals(Set.empty)
        _    <- groups.membersWithin(Seq(dept.id)).assertEquals(List(bob.id))
        _    <- groups.membersWithin(Seq.empty).assertEquals(List.empty)
      yield ()

  test(
    "a user may address themselves, their groups, and those groups' members",
  ):
    withStores("addressable"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        member   <- newUser(users, "member")
        invited  <- newUser(users, "invited")
        stranger <- newUser(users, "stranger")
        other    <- newUser(users, "other")
        dept     <- groups.create(owner.id, GroupDraft("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDraft("Retail", Some(dept.id)),
        )
        theirs <- groups.create(other.id, GroupDraft("Chess club"))
        _      <- join(groups, owner, team.id, member)
        _      <- groups.invite(owner.id, team.id, "invited")
        _      <- join(groups, other, theirs.id, stranger)
        listed <- groups.addressable(owner.id)
      yield assertEquals(
        listed,
        List(
          Principal.Person(owner.id),
          Principal.Group(dept.id),
          Principal.Group(team.id),
          Principal.Person(member.id),
        ),
      )

  test("an owner in their own group is listed once"):
    withStores("addressable-self"): (groups, users) =>
      for
        owner  <- newUser(users, "manager")
        team   <- groups.create(owner.id, GroupDraft("Retail"))
        _      <- join(groups, owner, team.id, owner)
        listed <- groups.addressable(owner.id)
      yield assertEquals(
        listed,
        List(
          Principal.Person(owner.id),
          Principal.Group(team.id),
        ),
      )

  test("whether a user may address someone agrees with the list"):
    withStores("may-address"): (groups, users) =>
      for
        owner    <- newUser(users, "manager")
        member   <- newUser(users, "member")
        invited  <- newUser(users, "invited")
        stranger <- newUser(users, "stranger")
        other    <- newUser(users, "other")
        dept     <- groups.create(owner.id, GroupDraft("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDraft("Retail", Some(dept.id)),
        )
        theirs <- groups.create(other.id, GroupDraft("Chess club"))
        _      <- join(groups, owner, team.id, member)
        _      <- groups.invite(owner.id, team.id, "invited")
        listed <- groups.addressable(owner.id)
        candidates = List(
          Principal.Person(owner.id),
          Principal.Person(member.id),
          Principal.Person(invited.id),
          Principal.Person(stranger.id),
          Principal.Group(dept.id),
          Principal.Group(team.id),
          Principal.Group(theirs.id),
        )
        answers <- candidates.traverse(groups.mayAddress(owner.id, _))
      yield
        assertEquals(
          candidates.zip(answers).toMap,
          Map(
            Principal.Person(owner.id)    -> true,
            Principal.Person(member.id)   -> true,
            Principal.Person(invited.id)  -> false,
            Principal.Person(stranger.id) -> false,
            Principal.Group(dept.id)      -> true,
            Principal.Group(team.id)      -> true,
            Principal.Group(theirs.id)    -> false,
          ),
        )
        assertEquals(
          candidates.filter(listed.contains),
          candidates
            .zip(answers)
            .collect { case (principal, true) => principal },
        )

  test("a member who leaves may no longer be addressed"):
    withStores("may-address-leave"): (groups, users) =>
      for
        owner  <- newUser(users, "manager")
        member <- newUser(users, "member")
        team   <- groups.create(owner.id, GroupDraft("Retail"))
        _      <- join(groups, owner, team.id, member)
        before <- groups.mayAddress(owner.id, Principal.Person(member.id))
        _      <- groups.leave(member.id, team.id)
        after  <- groups.mayAddress(owner.id, Principal.Person(member.id))
      yield
        assertEquals(before, true)
        assertEquals(after, false)
