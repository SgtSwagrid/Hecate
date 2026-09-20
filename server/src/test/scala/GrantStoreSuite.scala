package com.alecdorrington.hecate
package server

// The query syntax of the very profile the tables under test are built on.
import TestDb.tables.profile.api.*
import cats.effect.IO
import com.alecdorrington.hecate.model.{
  Access, Grant, Group, GroupDraft, Principal, Resource, User,
}
import munit.CatsEffectSuite

class GrantStoreSuite extends CatsEffectSuite:

  private val document      = Resource("document", 1)
  private val otherDocument = Resource("document", 2)

  /** Runs a check against a grant store over a fresh in-memory database. */
  private def withStore
    (name: String)
    (check: (GrantStore, TestDb) => IO[Unit])
    : IO[Unit] = TestDb
    .open(s"grants-$name")
    .use(db => check(GrantStore(TestDb.tables, db), db))

  /** Stores one grant, running the store's action to completion. */
  private def give
    (grants: GrantStore, db: TestDb)
    (
      resource: Resource,
      principal: Principal,
      access: Access,
    )
    : IO[Unit] = db.run(grants.grant(Grant(resource, principal, access)))

  /** Registers a user for tests, yielding their assigned identifier. */
  private def newUser(users: UserStore, name: String): IO[User] = users
    .register(name, "hash")
    .map(_.get)

  /** Invites a user to a group and has them accept, making them a member. */
  private def join
    (
      groups: GroupStore,
      owner: User,
      group: Group,
      user: User,
    )
    : IO[Unit] =
    for
      _     <- groups.invite(owner.id, group.id, user.username)
      found <- groups.invitations(user.id)
      _     <- groups.accept(
        user.id,
        found.find(_.group.id == group.id).get.id,
      )
    yield ()

  test("nothing reaches anyone over an ungranted resource"):
    withStore("none"): (grants, _) =>
      for
        access  <- grants.access(1, List(10), document)
        granted <- grants.grantsOver(document).map(_.nonEmpty)
      yield
        assertEquals(access, None)
        assertEquals(granted, false)

  test("a personal grant reaches its user and nobody else"):
    withStore("person"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Person(1),
          Access.Edit,
        )
        mine  <- grants.access(1, List.empty, document)
        other <- grants.access(2, List.empty, document)
      yield
        assertEquals(mine, Some(Access.Edit))
        assertEquals(other, None)

  test("a group grant reaches exactly the users given that group"):
    withStore("group"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Group(10),
          Access.View,
        )
        member  <- grants.access(1, List(10), document)
        outside <- grants.access(1, List(11), document)
      yield
        assertEquals(member, Some(Access.View))
        assertEquals(outside, None)

  test("the highest of every grant reaching a user wins"):
    withStore("highest"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Person(1),
          Access.View,
        )
        _ <- give(grants, db)(
          document,
          Principal.Group(10),
          Access.Own,
        )
        access <- grants.access(1, List(10), document)
      yield assertEquals(access, Some(Access.Own))

  test("a user and a group sharing an identifier are not confused"):
    withStore("discriminated"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Group(1),
          Access.Own,
        )
        access <- grants.access(1, List.empty, document)
      yield assertEquals(access, None)

  test("regranting replaces a principal's access rather than adding to it"):
    withStore("regrant"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give(grants, db)(
          document,
          Principal.Person(1),
          Access.View,
        )
        access <- grants.access(1, List.empty, document)
        all    <- grants.grantsOver(document)
      yield
        assertEquals(access, Some(Access.View))
        assertEquals(
          all,
          List(Grant(
            document,
            Principal.Person(1),
            Access.View,
          )),
        )

  test("revoking one principal leaves every other principal's grant"):
    withStore("revoke"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Person(1),
          Access.Edit,
        )
        _ <- give(grants, db)(
          document,
          Principal.Person(2),
          Access.Edit,
        )
        _    <- db.run(grants.revoke(document, Principal.Person(1)))
        gone <- grants.access(1, List.empty, document)
        kept <- grants.access(2, List.empty, document)
      yield
        assertEquals(gone, None)
        assertEquals(kept, Some(Access.Edit))

  test("revoking everything over a resource leaves other resources alone"):
    withStore("revoke-all"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give(grants, db)(
          document,
          Principal.Group(10),
          Access.View,
        )
        _ <- give(grants, db)(
          otherDocument,
          Principal.Person(1),
          Access.Own,
        )
        _    <- db.run(grants.revokeAll(document))
        gone <- grants.grantsOver(document).map(_.nonEmpty)
        kept <- grants.grantsOver(otherDocument).map(_.nonEmpty)
      yield
        assertEquals(gone, false)
        assertEquals(kept, true)

  test("revoking what principals hold spares other principals of any kind"):
    withStore("revoke-held"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Group(10),
          Access.View,
        )
        _ <- give(grants, db)(
          otherDocument,
          Principal.Group(10),
          Access.Edit,
        )
        _ <- give(grants, db)(
          document,
          Principal.Group(11),
          Access.View,
        )
        _ <- give(grants, db)(
          document,
          Principal.Person(10),
          Access.Edit,
        )
        _     <- db.run(grants.revokeHeldBy(List(Principal.Group(10))))
        held  <- grants.grantsOver(document)
        other <- grants.grantsOver(otherDocument)
      yield
        assertEquals(
          held.map(_.principal).toSet,
          Set(
            Principal.Group(11),
            Principal.Person(10),
          ),
        )
        assertEquals(other, List.empty)

  test("revoking what no principals hold changes nothing"):
    withStore("revoke-none"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Group(10),
          Access.View,
        )
        _   <- db.run(grants.revokeHeldBy(List.empty))
        all <- grants.grantsOver(document)
      yield assertEquals(all.size, 1)

  test("visible lists resources of one kind held at or above a level"):
    withStore("visible"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give(grants, db)(
          otherDocument,
          Principal.Group(10),
          Access.View,
        )
        _ <- give(grants, db)(
          Resource("folder", 1),
          Principal.Person(1),
          Access.Own,
        )
        edits  <- grants.visible(1, List(10), "document", Access.Edit)
        views  <- grants.visible(1, List(10), "document", Access.View)
        others <- grants.visible(2, List.empty, "document", Access.View)
      yield
        assertEquals(edits, Set(1L))
        assertEquals(views, Set(1L, 2L))
        assertEquals(others, Set.empty[Long])

  test("levels map each resource of one kind to the highest access held"):
    withStore("levels"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Person(1),
          Access.View,
        )
        _ <- give(grants, db)(
          document,
          Principal.Group(10),
          Access.Own,
        )
        _ <- give(grants, db)(
          otherDocument,
          Principal.Group(10),
          Access.Edit,
        )
        _ <- give(grants, db)(
          Resource("folder", 3),
          Principal.Person(1),
          Access.Own,
        )
        held <- grants.levels(1, List(10), "document")
      yield assertEquals(
        held,
        Map(1L -> Access.Own, 2L -> Access.Edit),
      )

  test("a stored level this version cannot read confers nothing"):
    withStore("unreadable"): (grants, db) =>
      for
        _ <- db.run(
          TestDb.tables.grants +=
            GrantRow(0, "document", 1, "person", 1, "admin"),
        )
        access <- grants.access(1, List.empty, document)
        all    <- grants.grantsOver(document)
      yield
        assertEquals(access, None)
        assertEquals(all, List.empty)

  test("a grant to an enclosing group reaches nested members, not the reverse"):
    TestDb
      .open("grants-nesting")
      .use: db =>
        val groups      = GroupStore(TestDb.tables, db)
        val users       = UserStore(TestDb.tables, db)
        val grants      = GrantStore(TestDb.tables, db)
        val permissions = Permissions(groups, grants)
        for
          owner <- newUser(users, "manager")
          inner <- newUser(users, "inner")
          outer <- newUser(users, "outer")
          dept  <- groups.create(owner.id, GroupDraft("Sales"))
          team  <- groups.create(
            owner.id,
            GroupDraft("Retail", Some(dept.id)),
          )
          _ <- join(groups, owner, team, inner)
          _ <- join(groups, owner, dept, outer)
          _ <- give(grants, db)(
            document,
            Principal.Group(dept.id),
            Access.View,
          )
          _ <- give(grants, db)(
            otherDocument,
            Principal.Group(team.id),
            Access.Edit,
          )
          down <- permissions.access(inner.id, document)
          up   <- permissions.access(outer.id, otherDocument)
        yield
          assertEquals(down, Some(Access.View))
          assertEquals(up, None)

  test("an invitation, pending or declined, never satisfies a group grant"):
    TestDb
      .open("grants-invited")
      .use: db =>
        val groups      = GroupStore(TestDb.tables, db)
        val users       = UserStore(TestDb.tables, db)
        val grants      = GrantStore(TestDb.tables, db)
        val permissions = Permissions(groups, grants)
        for
          owner    <- newUser(users, "manager")
          employee <- newUser(users, "employee")
          team     <- groups.create(owner.id, GroupDraft("Retail"))
          _        <- give(grants, db)(
            document,
            Principal.Group(team.id),
            Access.View,
          )
          _        <- groups.invite(owner.id, team.id, employee.username)
          pending  <- permissions.access(employee.id, document)
          invited  <- groups.invitations(employee.id)
          _        <- groups.decline(employee.id, invited.head.id)
          declined <- permissions.access(employee.id, document)
          _        <- groups.invite(owner.id, team.id, employee.username)
          again    <- groups.invitations(employee.id)
          _        <- groups.accept(employee.id, again.head.id)
          accepted <- permissions.access(employee.id, document)
        yield
          assertEquals(pending, None)
          assertEquals(declined, None)
          assertEquals(accepted, Some(Access.View))

  test("a sole owner is told which resources would be left ownerless"):
    withStore("sole"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give(grants, db)(
          otherDocument,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give(grants, db)(
          otherDocument,
          Principal.Person(2),
          Access.Own,
        )
        _ <- give(grants, db)(
          Resource("folder", 5),
          Principal.Person(1),
          Access.Edit,
        )
        _ <- give(grants, db)(
          Resource("folder", 6),
          Principal.Person(3),
          Access.Own,
        )
        sole <- db.run(grants.soleOwnerOf(Seq(Principal.Person(1))))
      yield assertEquals(sole, Seq(document))

  test("a group holding Own counts as another owner"):
    withStore("sole-group"): (grants, db) =>
      for
        _ <- give(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give(grants, db)(
          document,
          Principal.Group(1),
          Access.Own,
        )
        sole <- db.run(grants.soleOwnerOf(Seq(Principal.Person(1))))
      yield assertEquals(sole, Seq.empty)

  test("reading the grants over a resource in a transaction agrees with out"):
    withStore("granted"): (grants, db) =>
      val give_ = give(grants, db)
      for
        _ <- give_(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give_(
          document,
          Principal.Group(2),
          Access.View,
        )
        _ <- give_(
          otherDocument,
          Principal.Person(3),
          Access.Edit,
        )
        inside <- db.run(grants.granted(document))
        beside <- grants.grantsOver(document)
      yield
        assertEquals(inside.toSet, beside.toSet)
        assertEquals(
          inside.map(_.principal).toSet,
          Set(
            Principal.Person(1),
            Principal.Group(2),
          ),
        )

  test("a grant composed into one transaction is read back by it"):
    withStore("granted-composed"): (grants, db) =>
      val write = grants.grant(Grant(
        document,
        Principal.Person(1),
        Access.Edit,
      ))
      db.run(write.andThen(grants.granted(document)).transactionally)
        .map(held => assertEquals(held.map(_.access), List(Access.Edit)))
