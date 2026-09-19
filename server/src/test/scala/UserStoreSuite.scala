package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

class UserStoreSuite extends CatsEffectSuite:

  /** Runs a check against a store backed by a fresh in-memory database. */
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

  test("an unknown username is not found"):
    withStore("unknown")(_.findByUsername("nobody").assertEquals(None))

  test("an open session resolves to its user"):
    withStore("session"): users =>
      for
        user <- users.register("alice", "hash").map(_.get)
        _    <- users.openSession("token", user.id, UserStoreSuite.soon)
        held <- users.sessionUser("token")
      yield assertEquals(held, Some(user))

  test("an unknown session token resolves to nobody"):
    withStore("no-session")(_.sessionUser("bogus").assertEquals(None))

  test("a closed session no longer resolves"):
    withStore("closed"): users =>
      for
        user <- users.register("alice", "hash").map(_.get)
        _    <- users.openSession("token", user.id, UserStoreSuite.soon)
        _    <- users.closeSession("token")
        held <- users.sessionUser("token")
      yield assertEquals(held, None)

  test("an expired session no longer resolves"):
    withStore("expired"): users =>
      for
        user <- users.register("alice", "hash").map(_.get)
        _    <- users.openSession("token", user.id, UserStoreSuite.past)
        held <- users.sessionUser("token")
      yield assertEquals(held, None)

  test("purging clears out the expired sessions and keeps the live ones"):
    withStore("sweep"): users =>
      for
        user <- users.register("alice", "hash").map(_.get)
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
        row   <- users.findById(user.id)
        old1  <- users.sessionUser("laptop")
        old2  <- users.sessionUser("phone")
        fresh <- users.sessionUser("fresh")
      yield
        assertEquals(row.map(_.passwordHash), Some("new"))
        assertEquals((old1, old2), (None, None))
        assertEquals(fresh, Some(user))

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
        row  <- users.findById(user.id)
        left <- users.recoveryCodesLeft(user.id)
      yield
        assertEquals(first, Some(user))
        assertEquals(again, None)
        assertEquals(row.map(_.passwordHash), Some("new"))
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
        row  <- users.findById(user.id)
        left <- users.recoveryCodesLeft(user.id)
      yield
        assertEquals((wrong, unknown), (None, None))
        assertEquals(row.map(_.passwordHash), Some("old"))
        assertEquals(left, 1)

  test("users are looked up by identifier, with unknown identifiers left out"):
    withStore("named"): users =>
      for
        alice <- users.register("alice", "hash").map(_.get)
        bob   <- users.register("bob", "hash").map(_.get)
        found <- users.named(List(alice.id, bob.id, alice.id, 9999L))
        none  <- users.named(List.empty)
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

  /** An expiry time comfortably in the future. */
  private val soon = System.currentTimeMillis + 60000

  /** An expiry time in the past. */
  private val past = System.currentTimeMillis - 1
