package com.alecdorrington.hecate
package server

import Fixtures.cheap
import cats.effect.{IO, Ref}
import com.alecdorrington.hecate.api.Protocol
import com.alecdorrington.hecate.model.{
  AuthRefusal, AuthRules, Credentials, PasswordChange,
}
import munit.CatsEffectSuite

class AuthServiceSuite extends CatsEffectSuite:

  import AuthServiceSuite.*

  test("registering answers the new user and opens a session"):
    serving(): auth =>
      for
        registered <- auth.register(alice)
        (user, session) = registered.toOption.get
        found <- auth.current(Some(session.token))
      yield
        assertEquals(user.username, "alice")
        assertEquals(session.maxAge, cheap.sessionSeconds)
        assertEquals(
          found.map(_.map(_.id)),
          Right(Some(user.id)),
        )

  test("nobody is signed in without a session"):
    serving(): auth =>
      for
        current  <- auth.current(None)
        signedIn <- auth.signedIn(None)
      yield
        assertEquals(current, Right(None))
        assertEquals(signedIn, Left(AuthRefusal.SignedOut))

  test("a taken username is refused"):
    serving(): auth =>
      for
        _     <- auth.register(alice)
        again <- auth.register(Credentials("alice", "different2"))
      yield assertEquals(again, Left(AuthRefusal.UsernameTaken))

  test("a username differing from a taken one only in case is refused"):
    serving(): auth =>
      for
        _     <- auth.register(alice)
        again <- auth.register(Credentials("ALICE", "different2"))
      yield assertEquals(again, Left(AuthRefusal.UsernameTaken))

  test("signing in ignores the username's letter case"):
    serving(): auth =>
      for
        made   <- auth.register(Credentials("Alice", "hunter2222"))
        signed <- auth.signIn(Credentials("aLiCe", "hunter2222"))
      yield assertEquals(signed.map(_._1), made.map(_._1))

  test("a password shorter than the policy allows is refused"):
    serving(): auth =>
      auth
        .register(Credentials("alice", "short"))
        .map(answer =>
          assertEquals(
            answer,
            Left(AuthRefusal.PasswordTooShort(8)),
          ),
        )

  test("a blank username is refused"):
    serving(): auth =>
      auth
        .register(Credentials("   ", "hunter2222"))
        .map(answer =>
          assertEquals(
            answer,
            Left(AuthRefusal.UsernameEmpty),
          ),
        )

  test("text longer than the protocol allows is refused, and never stored"):
    serving(): auth =>
      val name     = "a" * (Protocol.maxNameLength + 1)
      val password = "a" * (Protocol.maxPasswordLength + 1)
      for
        longName     <- auth.register(Credentials(name, "hunter2222"))
        longPassword <- auth.signIn(Credentials("alice", password))
        found        <- auth.signIn(Credentials(name, "hunter2222"))
      yield
        assertEquals(
          longName,
          Left(AuthRefusal.TooLong(Protocol.maxNameLength)),
        )
        assertEquals(
          longPassword,
          Left(AuthRefusal.TooLong(Protocol.maxPasswordLength)),
        )
        assert(
          found.isLeft,
          "an over-long username was registered",
        )

  test("signing in needs the right password"):
    serving(): auth =>
      for
        _     <- auth.register(alice)
        wrong <- auth.signIn(Credentials("alice", "hunter3333"))
        right <- auth.signIn(alice)
      yield
        assertEquals(
          wrong,
          Left(AuthRefusal.CredentialsIncorrect),
        )
        assert(
          right.isRight,
          "a correct sign-in was refused",
        )

  test(
    "signing in derives a password again under the policy's iterations alone",
  ):
    TestDb
      .users("auth-rehash")
      .use: users =>
        def hashingAt(iterations: Int) = AuthService(
          users,
          AuthPolicy(hashIterations = iterations),
        )
        val stored = users
          .findByUsername("alice")
          .map(_.flatMap(_.passwordHash).map(_.takeWhile(_ != ':')))
        for
          _      <- hashingAt(1000).register(alice)
          _      <- hashingAt(1000).signIn(alice)
          kept   <- stored
          _      <- hashingAt(2000).signIn(alice)
          raised <- stored
        yield
          assertEquals(kept, Some("1000"))
          assertEquals(raised, Some("2000"))

  test("an unknown username is refused exactly as a wrong password is"):
    serving(): auth =>
      for
        _       <- auth.register(alice)
        unknown <- auth.signIn(Credentials("nobody", "hunter2222"))
        wrong   <- auth.signIn(Credentials("alice", "hunter3333"))
      yield assertEquals(unknown, wrong)

  test("signing out closes the session it was given, and clears the cookie"):
    serving(): auth =>
      for
        registered <- auth.register(alice)
        token = registered.toOption.get._2.token
        closed <- auth.signOut(Some(token))
        after  <- auth.current(Some(token))
      yield
        assertEquals(closed, Right(SessionCookie.closed))
        assertEquals(after, Right(None))

  test("a new password closes every session and opens a fresh one"):
    serving(): auth =>
      for
        registered <- auth.register(alice)
        (user, first) = registered.toOption.get
        second  <- auth.signIn(alice)
        changed <- auth.changePassword(
          user,
          PasswordChange("hunter2222", "hunter3333"),
        )
        firstNow  <- auth.current(Some(first.token))
        secondNow <- auth.current(Some(second.toOption.get._2.token))
        fresh     <- auth.current(changed.map(_.token).toOption)
      yield
        assertEquals(firstNow, Right(None))
        assertEquals(secondNow, Right(None))
        assertEquals(
          fresh.map(_.map(_.id)),
          Right(Some(user.id)),
        )

  test("the rules say what the policy is, and whether accounts can be deleted"):
    serving(): auth =>
      IO(assertEquals(
        auth.rules,
        AuthRules(8, false, false, false),
      ))

  test("an account is not deleted without a store to delete it"):
    serving(): auth =>
      for
        registered <- auth.register(alice)
        deleted    <- auth.deleteAccount(
          registered.toOption.get._1,
          "hunter2222",
        )
      yield assertEquals(
        deleted,
        Left(AuthRefusal.NoAccountDeletion),
      )

  test("a failure that is not the user's is reported, and answered as such"):
    for
      reported <- Ref.of[IO, List[Throwable]](Nil)
      answer   <- TestDb
        .open("auth-failed")
        .use(db => IO.pure(db))
        .flatMap(closed =>
          AuthService(
            UserStore(TestDb.tables, closed),
            cheap,
            report = error => reported.update(error :: _),
          ).current(Some("token")),
        )
      errors <- reported.get
    yield
      assertEquals(answer, Left(AuthRefusal.Failed))
      assertEquals(errors.size, 1)

  test("a session cookie is set for the whole site, and kept from scripts"):
    val cookie = SessionCookie("abc", 60)
    assertEquals(
      cookie.header,
      s"${ Protocol.sessionCookie }=abc; Max-Age=60; Path=/; HttpOnly; " +
        "SameSite=Strict",
    )
    assert(
      !cookie.toString.contains("abc"),
      "the token was written out",
    )

object AuthServiceSuite:

  private val alice = Credentials("alice", "hunter2222")

  /** Runs a check against a fresh service, hashing under few iterations. */
  private def serving()(check: AuthService => IO[Unit]): IO[Unit] = TestDb
    .users("auth-service")
    .use(users => check(AuthService(users, cheap)))
