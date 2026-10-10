package com.alecdorrington.hecate
package server

import Fixtures.{enrol, grant, reach, register}
import TestDb.tables.profile.api.*
import cats.effect.IO
import com.alecdorrington.hecate.model.{
  Access, Grant, GroupDetails, Principal, Resource,
}
import munit.CatsEffectSuite

class GrantStoreSuite extends CatsEffectSuite:

  private val document      = Resource("document", 1)
  private val otherDocument = Resource("document", 2)

  private def withStore
    (name: String)
    (check: (GrantStore, TestDb) => IO[Unit])
    : IO[Unit] = TestDb
    .open(s"grants-$name")
    .use(db => check(GrantStore(TestDb.tables, db), db))

  test("nothing reaches anyone over an ungranted resource"):
    withStore("none"): (grants, _) =>
      for
        access  <- grants.access(reach(1, 10), document)
        granted <- grants.over(document).map(_.nonEmpty)
      yield
        assertEquals(access, None)
        assertEquals(granted, false)

  test("a personal grant reaches its user and nobody else"):
    withStore("person"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Edit,
        )
        mine  <- grants.access(reach(1), document)
        other <- grants.access(reach(2), document)
      yield
        assertEquals(mine, Some(Access.Edit))
        assertEquals(other, None)

  test("a group grant reaches exactly the users given that group"):
    withStore("group"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Group(10),
          Access.View,
        )
        member  <- grants.access(reach(1, 10), document)
        outside <- grants.access(reach(1, 11), document)
      yield
        assertEquals(member, Some(Access.View))
        assertEquals(outside, None)

  test("the highest of every grant reaching a user wins"):
    withStore("highest"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.View,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Group(10),
          Access.Own,
        )
        access <- grants.access(reach(1, 10), document)
      yield assertEquals(access, Some(Access.Own))

  test("a user and a group sharing an identifier are not confused"):
    withStore("discriminated"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Group(1),
          Access.Own,
        )
        access <- grants.access(reach(1), document)
      yield assertEquals(access, None)

  test("regranting replaces a principal's access rather than adding to it"):
    withStore("regrant"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.View,
        )
        access <- grants.access(reach(1), document)
        all    <- grants.over(document)
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
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Edit,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Person(2),
          Access.Edit,
        )
        _    <- db.run(grants.revoke(document, Principal.Person(1)))
        gone <- grants.access(reach(1), document)
        kept <- grants.access(reach(2), document)
      yield
        assertEquals(gone, None)
        assertEquals(kept, Some(Access.Edit))

  test("revoking everything over a resource leaves other resources alone"):
    withStore("revoke-all"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Group(10),
          Access.View,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Person(1),
          Access.Own,
        )
        _    <- db.run(grants.revokeOver(document))
        gone <- grants.over(document).map(_.nonEmpty)
        kept <- grants.over(otherDocument).map(_.nonEmpty)
      yield
        assertEquals(gone, false)
        assertEquals(kept, true)

  test("the grants over several resources are read in one, and no others"):
    withStore("over-all"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Group(10),
          Access.View,
        )
        _ <- grant(grants, db)(
          Resource("document", 3),
          Principal.Person(2),
          Access.Own,
        )
        _ <- grant(grants, db)(
          Resource("folder", 1),
          Principal.Person(3),
          Access.Own,
        )
        both <- grants.overAll("document", List(1L, 2L, 2L))
        none <- grants.overAll("document", Nil)
      yield
        assertEquals(
          both.toSet,
          Set(
            Grant(
              document,
              Principal.Person(1),
              Access.Own,
            ),
            Grant(
              otherDocument,
              Principal.Group(10),
              Access.View,
            ),
          ),
        )
        assertEquals(none, Nil)

  test("revoking what principals hold spares other principals of any kind"):
    withStore("revoke-held"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Group(10),
          Access.View,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Group(10),
          Access.Edit,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Group(11),
          Access.View,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Person(10),
          Access.Edit,
        )
        _     <- db.run(grants.revokeHeldBy(List(Principal.Group(10))))
        held  <- grants.over(document)
        other <- grants.over(otherDocument)
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
        _ <- grant(grants, db)(
          document,
          Principal.Group(10),
          Access.View,
        )
        _   <- db.run(grants.revokeHeldBy(List.empty))
        all <- grants.over(document)
      yield assertEquals(all.size, 1)

  test("accessible lists resources of one kind held at or above a level"):
    withStore("accessible"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Group(10),
          Access.View,
        )
        _ <- grant(grants, db)(
          Resource("folder", 1),
          Principal.Person(1),
          Access.Own,
        )
        edits  <- grants.accessible(reach(1, 10), "document", Access.Edit)
        views  <- grants.accessible(reach(1, 10), "document", Access.View)
        others <- grants.accessible(reach(2), "document", Access.View)
      yield
        assertEquals(edits, Set(1L))
        assertEquals(views, Set(1L, 2L))
        assertEquals(others, Set.empty[Long])

  test("levels map each resource of one kind to the highest access held"):
    withStore("levels"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.View,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Group(10),
          Access.Own,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Group(10),
          Access.Edit,
        )
        _ <- grant(grants, db)(
          Resource("folder", 3),
          Principal.Person(1),
          Access.Own,
        )
        held <- grants.levels(reach(1, 10), "document")
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
        access <- grants.access(reach(1), document)
        all    <- grants.over(document)
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
          owner <- register(users, "manager")
          inner <- register(users, "inner")
          outer <- register(users, "outer")
          dept  <- groups.create(owner.id, GroupDetails("Sales"))
          team  <- groups.create(
            owner.id,
            GroupDetails("Retail", Some(dept.id)),
          )
          _ <- enrol(groups, owner, team.id, inner)
          _ <- enrol(groups, owner, dept.id, outer)
          _ <- grant(grants, db)(
            document,
            Principal.Group(dept.id),
            Access.View,
          )
          _ <- grant(grants, db)(
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
          owner    <- register(users, "manager")
          employee <- register(users, "employee")
          team     <- groups.create(owner.id, GroupDetails("Retail"))
          _        <- grant(grants, db)(
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
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Person(1),
          Access.Own,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Person(2),
          Access.Own,
        )
        _ <- grant(grants, db)(
          Resource("folder", 5),
          Principal.Person(1),
          Access.Edit,
        )
        _ <- grant(grants, db)(
          Resource("folder", 6),
          Principal.Person(3),
          Access.Own,
        )
        sole <- db.run(grants.ownedSolelyBy(Seq(Principal.Person(1))))
      yield assertEquals(sole, Seq(document))

  test("a group holding Own counts as another owner"):
    withStore("sole-group"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Group(1),
          Access.Own,
        )
        sole <- db.run(grants.ownedSolelyBy(Seq(Principal.Person(1))))
      yield assertEquals(sole, Seq.empty)

  test("a person owns alone only what nobody else holds anything over"):
    withStore("alone"): (grants, db) =>
      val give_ = grant(grants, db)
      val notes = Resource("note", 1)
      for
        _ <- give_(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give_(
          otherDocument,
          Principal.Person(1),
          Access.Own,
        )
        _ <- give_(
          otherDocument,
          Principal.Group(2),
          Access.View,
        )
        _     <- give_(notes, Principal.Person(1), Access.Own)
        alone <- db.run(grants.ownedAlone(1, "document").result)
        none  <- db.run(grants.ownedAlone(2, "document").result)
      yield
        assertEquals(alone, Seq(document.id))
        assertEquals(none, Seq.empty)

  test("reading the grants over a resource in a transaction agrees with out"):
    withStore("granted"): (grants, db) =>
      for
        _ <- grant(grants, db)(
          document,
          Principal.Person(1),
          Access.Own,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Group(2),
          Access.View,
        )
        _ <- grant(grants, db)(
          otherDocument,
          Principal.Person(3),
          Access.Edit,
        )
        inside <- db.run(grants.granted(document))
        beside <- grants.over(document)
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
