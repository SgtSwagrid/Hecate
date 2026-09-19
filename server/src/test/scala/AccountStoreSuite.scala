package com.alecdorrington.hecate
package server

import TestDb.tables.profile.api.*
import cats.effect.IO
import com.alecdorrington.hecate.model.{
  Access, Grant, GroupDraft, Principal, Resource,
}
import munit.CatsEffectSuite
import scala.concurrent.ExecutionContext

class AccountStoreSuite extends CatsEffectSuite:

  /** Runs the recording cascade's combinators inline, as the stores do. */
  private given ExecutionContext = ExecutionContext.parasitic

  /** An expiry time comfortably in the future. */
  private val soon = System.currentTimeMillis + 60000

  test("deleting an account removes the user and everything that is theirs"):
    TestDb
      .open("accounts-delete")
      .use: db =>
        // Recorded by the host cascade as the deletion runs.
        var cascaded                              = List.empty[Principal]
        val cascade: Seq[Principal] => DBIO[Unit] = principals =>
          DBIO.successful(()).map(_ => cascaded = cascaded ++ principals)
        val users    = UserStore(TestDb.tables, db)
        val groups   = GroupStore(TestDb.tables, db, cascade)
        val grants   = GrantStore(TestDb.tables, db)
        val accounts = AccountStore(
          TestDb.tables,
          db,
          users,
          groups,
          grants,
          cascade,
        )
        val document = Resource("document", 1)
        for
          alice  <- users.register("alice", "hash").map(_.get)
          bob    <- users.register("bob", "hash").map(_.get)
          _      <- users.openSession("alice-token", alice.id, soon)
          _      <- users.replaceRecoveryCodes(alice.id, List("one", "two"))
          hers   <- groups.create(alice.id, GroupDraft("Alice's team"))
          his    <- groups.create(bob.id, GroupDraft("Bob's team"))
          _      <- groups.invite(bob.id, his.id, "alice")
          invite <- groups.invitations(alice.id).map(_.head)
          _      <- groups.accept(alice.id, invite.id)
          _      <- groups.invite(alice.id, hers.id, "bob")
          // Edit rather than Own, as the only owner of something is refused.
          _ <- db.run(grants.grant(Grant(
            document,
            Principal.Person(alice.id),
            Access.Edit,
          )))
          _ <- db.run(grants.grant(Grant(
            document,
            Principal.Group(hers.id),
            Access.View,
          )))
          _ <- db.run(grants.grant(Grant(
            document,
            Principal.Person(bob.id),
            Access.View,
          )))
          _        <- accounts.delete(alice.id)
          gone     <- users.findById(alice.id)
          session  <- users.sessionUser("alice-token")
          codes    <- users.recoveryCodesLeft(alice.id)
          bobsView <- groups.list(bob.id)
          invited  <- groups.invitations(bob.id)
          left     <- grants.grantsOver(document)
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
    TestDb
      .open("accounts-sole-owner")
      .use: db =>
        val users    = UserStore(TestDb.tables, db)
        val groups   = GroupStore(TestDb.tables, db)
        val grants   = GrantStore(TestDb.tables, db)
        val accounts = AccountStore(
          TestDb.tables,
          db,
          users,
          groups,
          grants,
          _ => DBIO.successful(()),
        )
        val document = Resource("document", 1)
        for
          alice <- users.register("alice", "hash").map(_.get)
          hers  <- groups.create(alice.id, GroupDraft("Alice's team"))
          _     <- users.openSession("alice-token", alice.id, soon)
          _     <- db.run(grants.grant(Grant(
            document,
            Principal.Person(alice.id),
            Access.Own,
          )))
          refused <- accounts.delete(alice.id).attempt
          still   <- users.findById(alice.id)
          session <- users.sessionUser("alice-token")
          view    <- groups.list(alice.id)
          left    <- grants.grantsOver(document)
        yield
          assertEquals(
            refused.left.toOption.map(_.getMessage),
            Some(
              "You are the only owner of 1 thing. Give them to someone else, " +
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

  test("a co-owner can delete their account, and the other owner keeps theirs"):
    TestDb
      .open("accounts-co-owner")
      .use: db =>
        val users    = UserStore(TestDb.tables, db)
        val groups   = GroupStore(TestDb.tables, db)
        val grants   = GrantStore(TestDb.tables, db)
        val accounts = AccountStore(
          TestDb.tables,
          db,
          users,
          groups,
          grants,
          _ => DBIO.successful(()),
        )
        val document = Resource("document", 1)
        for
          alice <- users.register("alice", "hash").map(_.get)
          bob   <- users.register("bob", "hash").map(_.get)
          _     <- db.run(grants.grant(Grant(
            document,
            Principal.Person(alice.id),
            Access.Own,
          )))
          _ <- db.run(grants.grant(Grant(
            document,
            Principal.Person(bob.id),
            Access.Own,
          )))
          _    <- accounts.delete(alice.id)
          gone <- users.findById(alice.id)
          left <- grants.grantsOver(document)
        yield
          assertEquals(gone, None)
          assertEquals(
            left.map(_.principal),
            List(Principal.Person(bob.id)),
          )

  test("deleting one account leaves every other account alone"):
    TestDb
      .open("accounts-others")
      .use: db =>
        val users    = UserStore(TestDb.tables, db)
        val groups   = GroupStore(TestDb.tables, db)
        val grants   = GrantStore(TestDb.tables, db)
        val accounts = AccountStore(
          TestDb.tables,
          db,
          users,
          groups,
          grants,
          _ => DBIO.successful(()),
        )
        for
          alice <- users.register("alice", "hash").map(_.get)
          bob   <- users.register("bob", "hash").map(_.get)
          _     <- users.openSession("bob-token", bob.id, soon)
          his   <- groups.create(bob.id, GroupDraft("Bob's team"))
          _     <- accounts.delete(alice.id)
          still <- users.sessionUser("bob-token")
          view  <- groups.list(bob.id)
        yield
          assertEquals(still, Some(bob))
          assertEquals(view.map(_.group), List(his))
