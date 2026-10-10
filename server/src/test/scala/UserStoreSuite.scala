package com.alecdorrington.hecate
package server

import Fixtures.register
import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import slick.jdbc.H2Profile.api.*

class UserStoreSuite extends CatsEffectSuite:

  private def withStore(name: String)(check: UserStore => IO[Unit]): IO[Unit] =
    TestDb.users(s"users-$name").use(check)

  test("registering assigns an identifier and stores the user"):
    withStore("register"): users =>
      for
        user  <- users.register("alice", "hash")
        found <- users.findByUsername("alice")
      yield
        assertEquals(user.map(_.username), Some("alice"))
        assertEquals(found.map(_.toUser), user)

  test("a taken username cannot be registered again"):
    withStore("taken"): users =>
      users.register("alice", "hash") *>
        users.register("alice", "other").assertEquals(None)

  test("a username is taken in any letter case, and kept as chosen"):
    withStore("case"): users =>
      for
        user  <- users.register("Alice", "hash")
        again <- users.register("aLICE", "other")
        found <- users.findByUsername("ALICE")
      yield
        assertEquals(user.map(_.username), Some("Alice"))
        assertEquals(again, None)
        assertEquals(found.map(_.toUser), user)

  test("an unknown username is not found"):
    withStore("unknown")(_.findByUsername("nobody").assertEquals(None))

  test("a session is stored as the hash of its token, never the token"):
    TestDb
      .open("users-session-hash")
      .use: db =>
        val users = UserStore(TestDb.tables, db)
        for
          user   <- register(users, "alice")
          _      <- users.openSession("secret", user.id, UserStoreSuite.soon)
          stored <- db.run(TestDb.tables.sessions.result)
          found  <- users.sessionUser("secret")
        yield
          assertEquals(
            stored.map(_.tokenHash),
            Seq(Digest.of("secret")),
          )
          assertEquals(found, Some(user))

  test("an open session resolves to its user"):
    withStore("session"): users =>
      for
        user <- register(users, "alice")
        _    <- users.openSession("token", user.id, UserStoreSuite.soon)
        held <- users.sessionUser("token")
      yield assertEquals(held, Some(user))

  test("an unknown session token resolves to nobody"):
    withStore("no-session")(_.sessionUser("bogus").assertEquals(None))

  test("a closed session no longer resolves"):
    withStore("closed"): users =>
      for
        user <- register(users, "alice")
        _    <- users.openSession("token", user.id, UserStoreSuite.soon)
        _    <- users.closeSession("token")
        held <- users.sessionUser("token")
      yield assertEquals(held, None)

  test("an expired session no longer resolves"):
    withStore("expired"): users =>
      for
        user <- register(users, "alice")
        _    <- users.openSession("token", user.id, UserStoreSuite.past)
        held <- users.sessionUser("token")
      yield assertEquals(held, None)

  test("purging clears out the expired sessions and keeps the live ones"):
    withStore("sweep"): users =>
      for
        user <- register(users, "alice")
        _    <- users.openSession("stale", user.id, UserStoreSuite.past)
        _    <- users.openSession("fresh", user.id, UserStoreSuite.soon)
        _    <- users.purgeExpired
        // The stale row is gone, so a token that collides with it cannot be
        // rejected by the primary key.
        _     <- users.openSession("stale", user.id, UserStoreSuite.soon)
        held  <- users.sessionUser("stale")
        fresh <- users.sessionUser("fresh")
      yield assertEquals((held, fresh), (Some(user), Some(user)))

  test("resetting a password signs out every session and opens the given one"):
    withStore("reset"): users =>
      for
        user <- users.register("alice", "old").map(_.get)
        _    <- users.openSession("laptop", user.id, UserStoreSuite.soon)
        _    <- users.openSession("phone", user.id, UserStoreSuite.soon)
        _    <- users.resetPassword(
          user.id,
          "new",
          "fresh",
          UserStoreSuite.soon,
        )
        row    <- users.find(user.id)
        laptop <- users.sessionUser("laptop")
        phone  <- users.sessionUser("phone")
        fresh  <- users.sessionUser("fresh")
      yield
        assertEquals(
          row.flatMap(_.passwordHash),
          Some("new"),
        )
        assertEquals((laptop, phone), (None, None))
        assertEquals(fresh, Some(user))

  test("rehashing a password leaves every session where it was"):
    withStore("rehash"): users =>
      for
        user <- users.register("alice", "old").map(_.get)
        _    <- users.openSession("laptop", user.id, UserStoreSuite.soon)
        _    <- users.rehash(user.id, "stronger")
        row  <- users.find(user.id)
        open <- users.sessionUser("laptop")
      yield
        assertEquals(
          row.flatMap(_.passwordHash),
          Some("stronger"),
        )
        assertEquals(open, Some(user))

  test("a recovery code regains the account once, and only once"):
    withStore("recover"): users =>
      for
        user  <- users.register("alice", "old").map(_.get)
        _     <- users.replaceRecoveryCodes(user.id, List("code-hash"))
        first <- users.recover(
          "alice",
          "code-hash",
          "new",
          "t1",
          UserStoreSuite.soon,
        )
        again <- users.recover(
          "alice",
          "code-hash",
          "newer",
          "t2",
          UserStoreSuite.soon,
        )
        row  <- users.find(user.id)
        left <- users.recoveryCodesLeft(user.id)
      yield
        assertEquals(first, Some(user))
        assertEquals(again, None)
        assertEquals(
          row.flatMap(_.passwordHash),
          Some("new"),
        )
        assertEquals(left, 0)

  test(
    "a wrong code or an unknown username recovers nothing, and changes nothing",
  ):
    withStore("recover-wrong"): users =>
      for
        user  <- users.register("alice", "old").map(_.get)
        _     <- users.replaceRecoveryCodes(user.id, List("code-hash"))
        wrong <- users.recover(
          "alice",
          "not-it",
          "new",
          "t1",
          UserStoreSuite.soon,
        )
        unknown <- users.recover(
          "nobody",
          "code-hash",
          "new",
          "t2",
          UserStoreSuite.soon,
        )
        row  <- users.find(user.id)
        left <- users.recoveryCodesLeft(user.id)
      yield
        assertEquals((wrong, unknown), (None, None))
        assertEquals(
          row.flatMap(_.passwordHash),
          Some("old"),
        )
        assertEquals(left, 1)

  test("users are looked up by identifier, with unknown identifiers left out"):
    withStore("named"): users =>
      for
        alice <- register(users, "alice")
        bob   <- register(users, "bob")
        found <- users.byIds(List(alice.id, bob.id, alice.id, 9999L))
        none  <- users.byIds(List.empty)
      yield
        assertEquals(
          found,
          Map(alice.id -> alice, bob.id -> bob),
        )
        assertEquals(none, Map.empty)

  test("a fresh set of recovery codes invalidates the old ones"):
    withStore("codes-replace"): users =>
      for
        user <- users.register("alice", "old").map(_.get)
        _    <- users.replaceRecoveryCodes(user.id, List("old-1", "old-2"))
        _    <- users.replaceRecoveryCodes(user.id, List("new-1"))
        old  <- users.recover(
          "alice",
          "old-1",
          "new",
          "t",
          UserStoreSuite.soon,
        )
        left <- users.recoveryCodesLeft(user.id)
      yield
        assertEquals(old, None)
        assertEquals(left, 1)

object UserStoreSuite:

  private val soon = System.currentTimeMillis + 60000

  private val past = System.currentTimeMillis - 1
