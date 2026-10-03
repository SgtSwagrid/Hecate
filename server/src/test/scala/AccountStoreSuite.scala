package com.alecdorrington.hecate
package server

import Fixtures.{enrol, grant, register}
import TestDb.tables.profile.api.*
import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.hecate.model.{
  Access, GroupDetails, Principal, Resource,
}
import munit.CatsEffectSuite

class AccountStoreSuite extends CatsEffectSuite:

  import AccountStoreSuite.*

  test("deleting an account removes the user and everything that is theirs"):
    var cascaded = List.empty[Principal]
    val cascade: GrantStore => Seq[Principal] => DBIO[Unit] =
      _ => principals => DBIO.unit.map(_ => cascaded = cascaded ++ principals)
    withAccounts("delete", cascade): (users, groups, grants, accounts, db) =>
      for
        alice <- register(users, "alice")
        bob   <- register(users, "bob")
        _     <- users.openSession("alice-token", alice.id, soon)
        _     <- users.replaceRecoveryCodes(alice.id, List("one", "two"))
        hers  <- groups.create(alice.id, GroupDetails("Alice's team"))
        his   <- groups.create(bob.id, GroupDetails("Bob's team"))
        _     <- enrol(groups, bob, his.id, alice)
        _     <- groups.invite(alice.id, hers.id, "bob")
        // Edit rather than Own, as the only owner of something is refused.
        _ <- grant(grants, db)(
          document,
          Principal.Person(alice.id),
          Access.Edit,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Group(hers.id),
          Access.View,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Person(bob.id),
          Access.View,
        )
        _        <- accounts.delete(alice.id)
        gone     <- users.find(alice.id)
        session  <- users.sessionUser("alice-token")
        codes    <- users.recoveryCodesLeft(alice.id)
        bobsView <- groups.owned(bob.id)
        invited  <- groups.invitations(bob.id)
        left     <- grants.over(document)
      yield
        assertEquals(gone, None)
        assertEquals(session, None)
        assertEquals(codes, 0)
        assertEquals(
          bobsView.flatMap(_.members),
          List.empty,
        )
        assertEquals(invited, List.empty)
        assertEquals(
          left.map(_.principal),
          List(Principal.Person(bob.id)),
        )
        assertEquals(
          cascaded.toSet,
          Set(
            Principal.Group(hers.id),
            Principal.Person(alice.id),
          ),
        )

  test("the only owner of something cannot delete their account"):
    withAccounts("sole-owner"): (users, groups, grants, accounts, db) =>
      for
        alice <- register(users, "alice")
        hers  <- groups.create(alice.id, GroupDetails("Alice's team"))
        _     <- users.openSession("alice-token", alice.id, soon)
        _     <- grant(grants, db)(
          document,
          Principal.Person(alice.id),
          Access.Own,
        )
        refused <- accounts.delete(alice.id).attempt
        still   <- users.find(alice.id)
        session <- users.sessionUser("alice-token")
        view    <- groups.owned(alice.id)
        left    <- grants.over(document)
      yield
        assertEquals(
          refused.left.toOption.map(_.getMessage),
          Some(
            "You're the only owner of 1 thing. Give them to someone else, " +
              "or delete them, before deleting your account.",
          ),
        )
        assertEquals(still.map(_.toUser), Some(alice))
        assertEquals(session, Some(alice))
        assertEquals(view.map(_.group), List(hers))
        assertEquals(
          left.map(_.principal),
          List(Principal.Person(alice.id)),
        )

  test(
    "what the host's cascade deletes with an account is no reason to refuse",
  ):
    // The host deletes the draft with its owner; the report outlives her.
    val draft  = Resource("document", 1)
    val report = Resource("document", 2)
    val cascade: GrantStore => Seq[Principal] => DBIO[Unit] =
      grants => _ => grants.revokeOver(draft)
    withAccounts("cascaded", cascade): (users, _, grants, accounts, db) =>
      for
        alice <- register(users, "alice")
        _     <- List(draft, report).traverse(grant(grants, db)(
          _,
          Principal.Person(alice.id),
          Access.Own,
        ))
        refused <- accounts.delete(alice.id).attempt
        kept    <- grants.over(draft)
        _       <- db.run(grants.revokeOver(report))
        _       <- accounts.delete(alice.id)
        gone    <- users.find(alice.id)
      yield
        assert(refused.isLeft, refused)
        // A refusal rolls back what the cascade did, too.
        assertEquals(
          kept.map(_.principal),
          List(Principal.Person(alice.id)),
        )
        assertEquals(gone, None)

  test("a co-owner can delete their account, and the other owner keeps theirs"):
    withAccounts("co-owner"): (users, _, grants, accounts, db) =>
      for
        alice <- register(users, "alice")
        bob   <- register(users, "bob")
        _     <- grant(grants, db)(
          document,
          Principal.Person(alice.id),
          Access.Own,
        )
        _ <- grant(grants, db)(
          document,
          Principal.Person(bob.id),
          Access.Own,
        )
        _    <- accounts.delete(alice.id)
        gone <- users.find(alice.id)
        left <- grants.over(document)
      yield
        assertEquals(gone, None)
        assertEquals(
          left.map(_.principal),
          List(Principal.Person(bob.id)),
        )

  test("deleting one account leaves every other account alone"):
    withAccounts("others"): (users, groups, _, accounts, _) =>
      for
        alice <- register(users, "alice")
        bob   <- register(users, "bob")
        _     <- users.openSession("bob-token", bob.id, soon)
        his   <- groups.create(bob.id, GroupDetails("Bob's team"))
        _     <- accounts.delete(alice.id)
        still <- users.sessionUser("bob-token")
        view  <- groups.owned(bob.id)
      yield
        assertEquals(still, Some(bob))
        assertEquals(view.map(_.group), List(his))

object AccountStoreSuite:

  private val soon = System.currentTimeMillis + 60000

  private val document = Resource("document", 1)

  private def withAccounts
    (
      name: String,
      cascade: GrantStore => Seq[Principal] => DBIO[Unit] = _ => _ => DBIO.unit,
    )
    (
      check: (
        UserStore,
        GroupStore,
        GrantStore,
        AccountStore,
        TestDb,
      ) => IO[Unit],
    )
    : IO[Unit] = TestDb
    .open(s"accounts-$name")
    .use: db =>
      val users  = UserStore(TestDb.tables, db)
      val grants = GrantStore(TestDb.tables, db)
      val groups = GroupStore(TestDb.tables, db, cascade(grants))
      check(
        users,
        groups,
        grants,
        AccountStore(
          TestDb.tables,
          db,
          users,
          groups,
          grants,
          cascade(grants),
        ),
        db,
      )
