package com.alecdorrington.hecate
package server

import Fixtures.{enrol, grant, invitationTo, refusal, register}
// The tables' own profile: a concrete profile's `api` would bring a second,
// clashing set of extension methods into scope.
import TestDb.tables.profile.api.*
import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.hecate.model.{
  Access, GroupDetails, Holder, Principal, Resource,
}
import munit.CatsEffectSuite

class GroupStoreSuite extends CatsEffectSuite:

  private def withStores
    (name: String)
    (check: (GroupStore, UserStore) => IO[Unit])
    : IO[Unit] = TestDb.groups(s"groups-$name").use(check.tupled)

  test("a created group is listed back for its owner, initially empty"):
    withStores("create"): (groups, users) =>
      for
        owner <- register(users, "manager")
        made  <- groups.create(owner.id, GroupDetails("Sales"))
        all   <- groups.managed(owner.id)
      yield
        assertEquals(all.map(_.group), List(made))
        assertEquals(all.flatMap(_.members), List.empty)
        assertEquals(all.flatMap(_.invitees), List.empty)

  test("groups are only visible to their owner"):
    withStores("scoped"): (groups, users) =>
      for
        alice <- register(users, "alice")
        bob   <- register(users, "bob")
        _     <- groups.create(alice.id, GroupDetails("Sales"))
        seen  <- groups.managed(bob.id)
      yield assertEquals(seen, List.empty)

  test("groups nest to arbitrary depth via their parent"):
    withStores("nested"): (groups, users) =>
      for
        owner <- register(users, "manager")
        dept  <- groups.create(owner.id, GroupDetails("Sales"))
        team  <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        _ <- groups.create(
          owner.id,
          GroupDetails("Front desk", Some(team.id)),
        )
        all <- groups.managed(owner.id)
      yield assertEquals(
        all.map(view => (view.group.name, view.group.parentId)),
        List(
          ("Sales", None),
          ("Retail", Some(dept.id)),
          ("Front desk", Some(team.id)),
        ),
      )

  test("another owner's group cannot be used as a parent"):
    withStores("foreign-parent"): (groups, users) =>
      for
        alice   <- register(users, "alice")
        bob     <- register(users, "bob")
        theirs  <- groups.create(alice.id, GroupDetails("Sales"))
        attempt <- groups
          .create(
            bob.id,
            GroupDetails("Retail", Some(theirs.id)),
          )
          .attempt
      yield assert(attempt.isLeft)

  test("a group cannot be moved inside its own subtree"):
    withStores("cycle"): (groups, users) =>
      for
        owner <- register(users, "manager")
        dept  <- groups.create(owner.id, GroupDetails("Sales"))
        team  <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        onto <- groups
          .update(
            owner.id,
            dept.id,
            GroupDetails("Sales", Some(team.id)),
          )
          .attempt
        ontoSelf <- groups
          .update(
            owner.id,
            dept.id,
            GroupDetails("Sales", Some(dept.id)),
          )
          .attempt
      yield
        assert(onto.isLeft)
        assert(ontoSelf.isLeft)

  test("updating renames and moves a group"):
    withStores("update"): (groups, users) =>
      for
        owner <- register(users, "manager")
        dept  <- groups.create(owner.id, GroupDetails("Sales"))
        team  <- groups.create(owner.id, GroupDetails("Retail"))
        _     <- groups.update(
          owner.id,
          team.id,
          GroupDetails("Outlets", Some(dept.id)),
        )
        all <- groups.managed(owner.id)
      yield assertEquals(
        all.map(view => (view.group.name, view.group.parentId)),
        List(
          ("Sales", None),
          ("Outlets", Some(dept.id)),
        ),
      )

  test("deleting a group deletes its subtree, memberships and invitations"):
    withStores("delete"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        invited  <- register(users, "invited")
        dept     <- groups.create(owner.id, GroupDetails("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        keep    <- groups.create(owner.id, GroupDetails("Chess club"))
        _       <- enrol(groups, owner, team.id, employee)
        _       <- groups.invite(owner.id, team.id, "invited")
        _       <- groups.delete(owner.id, dept.id)
        all     <- groups.managed(owner.id)
        theirs  <- groups.enclosing(employee.id)
        pending <- groups.invitations(invited.id)
      yield
        assertEquals(all.map(_.group), List(keep))
        assertEquals(theirs, List.empty)
        assertEquals(pending, List.empty)

  test("another owner's group cannot be deleted"):
    withStores("foreign-delete"): (groups, users) =>
      for
        alice   <- register(users, "alice")
        bob     <- register(users, "bob")
        theirs  <- groups.create(alice.id, GroupDetails("Sales"))
        attempt <- groups.delete(bob.id, theirs.id).attempt
        all     <- groups.managed(alice.id)
      yield
        assert(attempt.isLeft)
        assertEquals(all.map(_.group), List(theirs))

  test("the deletion cascade sees the whole subtree, before it is deleted"):
    TestDb
      .open("groups-cascade")
      .use: db =>
        var cascaded  = List.empty[Long]
        var surviving = 0
        val groups    = GroupStore(
          TestDb.tables,
          db,
          principals =>
            val ids = principals.collect { case Principal.Group(id) => id }
            // The groups must still exist while the cascade runs.
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
          owner <- register(users, "manager")
          dept  <- groups.create(owner.id, GroupDetails("Sales"))
          team  <- groups.create(
            owner.id,
            GroupDetails("Retail", Some(dept.id)),
          )
          _ <- groups.create(owner.id, GroupDetails("Chess club"))
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
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- groups.invite(owner.id, team.id, "Employee")
        view     <- groups.managed(owner.id).map(_.head)
        sent     <- groups.invitations(employee.id)
        reached  <- groups.enclosing(employee.id)
        joined   <- groups.memberships(employee.id)
      yield
        assertEquals(view.members, List.empty)
        assertEquals(view.invitees, List(employee))
        assertEquals(sent.map(_.group.id), List(team.id))
        assertEquals(sent.map(_.inviter), List(Some(owner)))
        assertEquals(reached, List.empty)
        assertEquals(joined, List.empty)

  test("accepting an invitation makes a member, and the group reaches them"):
    withStores("accept"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        dept     <- groups.create(owner.id, GroupDetails("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        _    <- enrol(groups, owner, team.id, employee)
        view <- groups.managed(owner.id).map(_.find(_.group.id == team.id).get)
        sent <- groups.invitations(employee.id)
        reached <- groups.enclosing(employee.id)
      yield
        assertEquals(view.members, List(employee))
        assertEquals(view.invitees, List.empty)
        assertEquals(sent, List.empty)
        assertEquals(reached, List(dept.id, team.id).sorted)

  test("an invitation can be accepted only once, and only by its invitee"):
    withStores("accept-once"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        stranger <- register(users, "stranger")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        sent     <- invitationTo(groups, employee, team.id)
        stolen   <- refusal(groups.accept(stranger.id, sent.id))
        _        <- groups.accept(employee.id, sent.id)
        again    <- refusal(groups.accept(employee.id, sent.id))
        members  <- groups.managed(owner.id).map(_.flatMap(_.members))
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
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        _        <- groups.invite(owner.id, team.id, "employee")
        sent     <- groups.invitations(employee.id)
      yield assertEquals(sent.size, 1)

  test("inviting a member changes nothing"):
    withStores("invite-member"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- enrol(groups, owner, team.id, employee)
        _        <- groups.invite(owner.id, team.id, "employee")
        view     <- groups.managed(owner.id).map(_.head)
      yield
        assertEquals(view.members, List(employee))
        assertEquals(view.invitees, List.empty)

  test("declining deletes the invitation, and the user may be invited again"):
    withStores("decline"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        sent     <- invitationTo(groups, employee, team.id)
        _        <- groups.decline(employee.id, sent.id)
        declined <- groups.managed(owner.id).map(_.head.invitees)
        held     <- groups.invitations(employee.id)
        _        <- groups.invite(owner.id, team.id, "employee")
        again    <- groups.managed(owner.id).map(_.head.invitees)
      yield
        assertEquals(declined, List.empty)
        assertEquals(held, List.empty)
        assertEquals(again, List(employee))

  test("a declined invitation can no longer be accepted"):
    withStores("decline-accept"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        sent     <- invitationTo(groups, employee, team.id)
        _        <- groups.decline(employee.id, sent.id)
        late     <- refusal(groups.accept(employee.id, sent.id))
        members  <- groups.managed(owner.id).map(_.head.members)
      yield
        assertEquals(
          late,
          Some("That invitation doesn't exist."),
        )
        assertEquals(members, List.empty)

  test("leaving ends the membership, and the user may be invited again"):
    withStores("leave"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- enrol(groups, owner, team.id, employee)
        _        <- groups.leave(employee.id, team.id)
        joined   <- groups.memberships(employee.id)
        reached  <- groups.enclosing(employee.id)
        _        <- groups.invite(owner.id, team.id, "employee")
        view     <- groups.managed(owner.id).map(_.head)
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
          owner <- register(users, "manager")
          dept  <- groups.create(owner.id, GroupDetails("Sales"))
          team  <- groups.create(
            owner.id,
            GroupDetails("Retail", Some(dept.id)),
          )
          keep <- groups.create(owner.id, GroupDetails("Chess club"))
          _    <- grant(grants, db)(
            document,
            Principal.Group(team.id),
            Access.View,
          )
          _ <- grant(grants, db)(
            document,
            Principal.Group(keep.id),
            Access.View,
          )
          _    <- groups.delete(owner.id, dept.id)
          left <- grants.over(document)
        yield assertEquals(
          left.map(_.principal),
          List(Principal.Group(keep.id)),
        )

  test("an owner's removal is no refusal, so the user can be invited again"):
    withStores("remove"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- enrol(groups, owner, team.id, employee)
        _        <- groups.remove(owner.id, team.id, employee.id)
        removed  <- groups.managed(owner.id).map(_.head)
        _        <- groups.invite(owner.id, team.id, "employee")
        again    <- groups.managed(owner.id).map(_.head.invitees)
      yield
        assertEquals(removed.members, List.empty)
        assertEquals(removed.invitees, List.empty)
        assertEquals(again, List(employee))

  test("an owner can cancel a pending invitation"):
    withStores("cancel"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- groups.invite(owner.id, team.id, "employee")
        _        <- groups.remove(owner.id, team.id, employee.id)
        view     <- groups.managed(owner.id).map(_.head)
        sent     <- groups.invitations(employee.id)
      yield
        assertEquals(view.invitees, List.empty)
        assertEquals(sent, List.empty)

  test("an unknown username cannot be invited"):
    withStores("invite-unknown"): (groups, users) =>
      for
        owner   <- register(users, "manager")
        team    <- groups.create(owner.id, GroupDetails("Retail"))
        refused <- refusal(groups.invite(owner.id, team.id, "nobody"))
      yield assertEquals(
        refused,
        Some("No user is called \"nobody\"."),
      )

  test("inviting into another's group reads like a group that does not exist"):
    withStores("invite-foreign"): (groups, users) =>
      for
        alice   <- register(users, "alice")
        bob     <- register(users, "bob")
        _       <- register(users, "employee")
        theirs  <- groups.create(alice.id, GroupDetails("Sales"))
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
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        dept     <- groups.create(owner.id, GroupDetails("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        row <- groups.create(
          owner.id,
          GroupDetails("Front desk", Some(team.id)),
        )
        _      <- groups.create(owner.id, GroupDetails("Chess club"))
        _      <- enrol(groups, owner, row.id, employee)
        theirs <- groups.enclosing(employee.id)
        named  <- groups.names(theirs :+ 999L)
      yield
        assertEquals(
          theirs,
          List(dept.id, team.id, row.id).sorted,
        )
        assertEquals(
          named,
          Map(
            dept.id -> "Sales",
            team.id -> "Retail",
            row.id  -> "Front desk",
          ),
        )

  test("effective groups are the member's own, whoever else owns groups"):
    withStores("ancestors-other-owners"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        stranger <- register(users, "stranger")
        employee <- register(users, "employee")
        dept     <- groups.create(owner.id, GroupDetails("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        // Another owner's forest, which the walk must neither read nor reach.
        theirs <- groups.create(stranger.id, GroupDetails("Accounts"))
        _      <- groups.create(
          stranger.id,
          GroupDetails("Payroll", Some(theirs.id)),
        )
        _    <- enrol(groups, owner, team.id, employee)
        mine <- groups.enclosing(employee.id)
        none <- groups.enclosing(stranger.id)
      yield
        assertEquals(mine, List(dept.id, team.id).sorted)
        assertEquals(none, List.empty)

  test("a membership of a group that no longer exists reaches nothing"):
    TestDb
      .open("groups-orphan")
      .use: db =>
        val groups = GroupStore(TestDb.tables, db)
        val users  = UserStore(TestDb.tables, db)
        for
          owner    <- register(users, "manager")
          employee <- register(users, "employee")
          team     <- groups.create(owner.id, GroupDetails("Retail"))
          _        <- enrol(groups, owner, team.id, employee)
          // Deleted behind the store's back, leaving a dangling membership.
          _ <- db.run(TestDb.tables.groups.filter(_.id === team.id).delete)
          reached <- groups.enclosing(employee.id)
        yield assertEquals(reached, List.empty)

  test("a user can see the groups they belong to"):
    withStores("memberships"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        dept     <- groups.create(owner.id, GroupDetails("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        _    <- groups.create(owner.id, GroupDetails("Chess club"))
        _    <- enrol(groups, owner, team.id, employee)
        mine <- groups.memberships(employee.id)
      // Only the group joined: they cannot leave an enclosing group.
      yield assertEquals(mine.map(_.group), List(team))

  test("a membership names the groups enclosing it, outermost first"):
    withStores("enclosing"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        company  <- groups.create(owner.id, GroupDetails("Company"))
        dept     <- groups.create(
          owner.id,
          GroupDetails("Sales", Some(company.id)),
        )
        team <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        _    <- enrol(groups, owner, team.id, employee)
        _    <- groups.join(owner.id, company.id)
        mine <- groups.memberships(employee.id)
      yield
        assertEquals(
          mine.map(_.enclosing.map(_.name)),
          List(List("Company", "Sales")),
        )
        assertEquals(
          mine.flatMap(_.members.map(_.id)),
          List(employee.id),
        )

  test("the members of a group see its owner and one another"):
    withStores("fellows"): (groups, users) =>
      for
        owner   <- register(users, "manager")
        alice   <- register(users, "alice")
        bob     <- register(users, "bob")
        invitee <- register(users, "invitee")
        asker   <- register(users, "asker")
        outside <- register(users, "outsider")
        team    <- groups.create(owner.id, GroupDetails("Retail"))
        other   <- groups.create(owner.id, GroupDetails("Chess club"))
        _       <- groups.setPublic(owner.id, team.id, public = true)
        _       <- enrol(groups, owner, team.id, alice)
        _       <- enrol(groups, owner, team.id, bob)
        _       <- enrol(groups, owner, other.id, outside)
        _       <- groups.invite(owner.id, team.id, invitee.username)
        _       <- groups.request(asker.id, team.id)
        seen    <- groups.memberships(alice.id)
        mirror  <- groups.memberships(bob.id)
        pending <- groups.memberships(invitee.id)
      yield
        assertEquals(seen.map(_.group.id), List(team.id))
        assertEquals(
          seen.map(_.owners),
          List(List(Holder(
            Principal.Person(owner.id),
            Some(owner.username),
          ))),
        )
        // Members only: neither the invitee nor the asker has joined, and
        // the member of another group is none of theirs.
        assertEquals(
          seen.flatMap(_.members),
          List(alice, bob),
        )
        assertEquals(mirror, seen)
        assertEquals(pending, List.empty)

  test("a member sees neither a subgroup's members nor an enclosing group's"):
    withStores("fellows-nested"): (groups, users) =>
      for
        owner <- register(users, "manager")
        upper <- register(users, "upper")
        lower <- register(users, "lower")
        dept  <- groups.create(owner.id, GroupDetails("Sales"))
        team  <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        _     <- enrol(groups, owner, dept.id, upper)
        _     <- enrol(groups, owner, team.id, lower)
        above <- groups.memberships(upper.id)
        below <- groups.memberships(lower.id)
      yield
        assertEquals(above.flatMap(_.members), List(upper))
        assertEquals(below.flatMap(_.members), List(lower))

  test("a member who leaves is seen by the others no longer"):
    withStores("fellows-leave"): (groups, users) =>
      for
        owner <- register(users, "manager")
        alice <- register(users, "alice")
        bob   <- register(users, "bob")
        team  <- groups.create(owner.id, GroupDetails("Retail"))
        _     <- enrol(groups, owner, team.id, alice)
        _     <- enrol(groups, owner, team.id, bob)
        _     <- groups.leave(bob.id, team.id)
        seen  <- groups.memberships(alice.id)
      yield assertEquals(seen.flatMap(_.members), List(alice))

  test("leaving a group one is not in changes nothing"):
    withStores("leave-absent"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        employee <- register(users, "employee")
        other    <- register(users, "other")
        team     <- groups.create(owner.id, GroupDetails("Retail"))
        _        <- enrol(groups, owner, team.id, employee)
        _        <- groups.leave(other.id, team.id)
        view     <- groups.managed(owner.id).map(_.head)
      yield
        assertEquals(view.members, List(employee))
        assertEquals(view.invitees, List.empty)

  test(
    "the owners of a member's groups may address them, and reach them within",
  ):
    withStores("addressers"): (groups, users) =>
      for
        alice <- register(users, "alice")
        bob   <- register(users, "bob")
        carol <- register(users, "carol")
        dept  <- groups.create(alice.id, GroupDetails("Sales"))
        team  <- groups.create(
          alice.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        _ <- enrol(groups, alice, team.id, bob)
        _ <- groups.addressersOf(bob.id).assertEquals(Set(alice.id))
        _ <- groups.addressersOf(carol.id).assertEquals(Set.empty)
        _ <- groups.membersWithin(Seq(dept.id)).assertEquals(List(bob.id))
        _ <- groups.membersWithin(Seq.empty).assertEquals(List.empty)
      yield ()

  test(
    "a user may address themselves, their groups, and those groups' members",
  ):
    withStores("addressable"): (groups, users) =>
      for
        owner    <- register(users, "manager")
        member   <- register(users, "member")
        invited  <- register(users, "invited")
        stranger <- register(users, "stranger")
        other    <- register(users, "other")
        dept     <- groups.create(owner.id, GroupDetails("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        theirs <- groups.create(other.id, GroupDetails("Chess club"))
        _      <- enrol(groups, owner, team.id, member)
        _      <- groups.invite(owner.id, team.id, "invited")
        _      <- enrol(groups, other, theirs.id, stranger)
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
        owner  <- register(users, "manager")
        team   <- groups.create(owner.id, GroupDetails("Retail"))
        _      <- groups.join(owner.id, team.id)
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
        owner    <- register(users, "manager")
        member   <- register(users, "member")
        invited  <- register(users, "invited")
        stranger <- register(users, "stranger")
        other    <- register(users, "other")
        dept     <- groups.create(owner.id, GroupDetails("Sales"))
        team     <- groups.create(
          owner.id,
          GroupDetails("Retail", Some(dept.id)),
        )
        theirs <- groups.create(other.id, GroupDetails("Chess club"))
        _      <- enrol(groups, owner, team.id, member)
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
        owner  <- register(users, "manager")
        member <- register(users, "member")
        team   <- groups.create(owner.id, GroupDetails("Retail"))
        _      <- enrol(groups, owner, team.id, member)
        before <- groups.mayAddress(owner.id, Principal.Person(member.id))
        _      <- groups.leave(member.id, team.id)
        after  <- groups.mayAddress(owner.id, Principal.Person(member.id))
      yield
        assertEquals(before, true)
        assertEquals(after, false)
