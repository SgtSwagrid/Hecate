package com.alecdorrington.hecate
package server

import Fixtures.{read, register, serve, SendRequest}
import cats.effect.IO
import com.alecdorrington.hecate.api.AuthApi
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{Credentials, User}
import io.circe.syntax.*
import munit.CatsEffectSuite
import sttp.client3.{basicRequest, Response, UriContext}
import sttp.model.StatusCode

/**
  * Tests of the endpoints [[AuthService]] serves, as a caller meets them: what
  * a request answers, what it sets, and in whose language it is refused.
  */
class AuthServiceSuite extends CatsEffectSuite:

  import AuthServiceSuite.*

  test("registering answers the new user and opens a session"):
    served(): call =>
      for
        registered <- call(register("alice", "hunter2222"))
        user = read[User](registered)
        signed <- call(me)
      yield
        assertEquals(user.map(_.username), Right("alice"))
        assert(
          cookieOf(registered).isDefined,
          "no session cookie was set",
        )
        assertEquals(signed.code, StatusCode.Ok)

  test("nobody is signed in without the cookie"):
    served(): call =>
      call(me).map(answer => assertEquals(answer.body, Right(nobody)))

  test("a session cookie signs its owner in"):
    served(): call =>
      for
        opened <- call(register("alice", "hunter2222"))
        token = cookieOf(opened).get
        found <- call(me.cookie(AuthApi.sessionCookie, token))
      yield assertEquals(
        read[Option[User]](found).map(_.map(_.username)),
        Right(Some("alice")),
      )

  test("a taken username is refused, and nothing is opened"):
    served(): call =>
      for
        _     <- call(register("alice", "hunter2222"))
        again <- call(register("alice", "different2"))
      yield
        assertEquals(again.code, StatusCode.BadRequest)
        assertEquals(
          again.body,
          Left(Wording.english.usernameTaken),
        )
        assertEquals(cookieOf(again), None)

  test("a password shorter than the policy allows is refused"):
    served(): call =>
      call(register("alice", "short")).map(answer =>
        assertEquals(
          answer.body,
          Left(Wording.english.passwordTooShort(8)),
        ),
      )

  test("a blank username is refused"):
    served(): call =>
      call(register("   ", "hunter2222")).map(answer =>
        assertEquals(
          answer.body,
          Left(Wording.english.emptyUsername),
        ),
      )

  test("signing in needs the right password"):
    served(): call =>
      for
        _     <- call(register("alice", "hunter2222"))
        wrong <- call(login("alice", "hunter3333"))
        right <- call(login("alice", "hunter2222"))
      yield
        assertEquals(
          wrong.body,
          Left(Wording.english.incorrectCredentials),
        )
        assertEquals(cookieOf(wrong), None)
        assert(
          cookieOf(right).isDefined,
          "a correct sign-in opened nothing",
        )

  /**
    * A password stored under fewer rounds than the policy asks for is derived
    * again under the policy's count when its owner signs in, and one stored
    * under as many is left alone: never under the library's own default, which
    * would override the host's choice either way.
    */
  test("signing in derives a password again under the policy's rounds alone"):
    TestDb
      .users(s"auth-rehash-${ java.util.UUID.randomUUID }")
      .use: users =>
        val rounds = users
          .findByUsername("alice")
          .map(_.map(_.passwordHash.takeWhile(_ != ':')))
        for
          _      <- hashingAt(users, 1000)(register("alice", "hunter2222"))
          _      <- hashingAt(users, 1000)(login("alice", "hunter2222"))
          kept   <- rounds
          _      <- hashingAt(users, 2000)(login("alice", "hunter2222"))
          raised <- rounds
        yield
          assertEquals(kept, Some("1000"))
          assertEquals(raised, Some("2000"))

  test("an unknown username is refused exactly as a wrong password is"):
    served(): call =>
      for
        _       <- call(register("alice", "hunter2222"))
        unknown <- call(login("nobody", "hunter2222"))
        wrong   <- call(login("alice", "hunter3333"))
      yield assertEquals(unknown.body, wrong.body)

  test("signing out closes the session it was given"):
    served(): call =>
      for
        opened <- call(register("alice", "hunter2222"))
        token = cookieOf(opened).get
        _ <- call(
          basicRequest
            .post(uri"http://test/api/auth/logout")
            .cookie(AuthApi.sessionCookie, token),
        )
        after <- call(me.cookie(AuthApi.sessionCookie, token))
      yield assertEquals(after.body, Right(nobody))

  test("an endpoint that needs a user refuses when there is none"):
    served(): call =>
      call(basicRequest.get(uri"http://test/api/auth/recovery-codes")).map(
        answer =>
          assertEquals(
            answer.body,
            Left(Wording.english.signedOut),
          ),
      )

  test("a refusal is worded in the language the request asks for"):
    served(wording =
      locale => if locale.contains("de") then German else Wording.english,
    ): call =>
      for
        _      <- call(register("alice", "hunter2222"))
        german <- call(
          register("alice", "hunter2222").cookie(AuthApi.languageCookie, "de"),
        )
      yield assertEquals(
        german.body,
        Left(German.usernameTaken),
      )

  test("the rules say what the policy is, and whether accounts can be deleted"):
    served(): call =>
      call(basicRequest.get(uri"http://test/api/auth/rules")).map(answer =>
        assertEquals(
          answer.body,
          Right("""{"minPasswordLength":8,"accountDeletion":false}"""),
        ),
      )

  test("account deletion is not served at all without a store for it"):
    served(): call =>
      call(
        basicRequest
          .post(uri"http://test/api/auth/account/delete")
          .body("""{"password":"hunter2222"}"""),
      ).map(answer => assertEquals(answer.code, StatusCode.NotFound))

object AuthServiceSuite:

  /** A second language, for checking that a refusal is worded in one. */
  private object German extends Wording:

    override val signedOut: String            = "Anmeldung erforderlich."
    override val usernameTaken: String        = "Dieser Name ist vergeben."
    override val incorrectCredentials: String = "Name oder Passwort falsch."
    override val incorrectPassword: String    = "Passwort falsch."
    override val incorrectRecovery: String    = "Name oder Code falsch."
    override val noAccountDeletion: String    = "Konten bleiben bestehen."
    override val emptyUsername: String        = "Name fehlt."
    override val groupMissing: String         = "Gruppe nicht vorhanden."
    override val invitationMissing: String    = "Einladung nicht vorhanden."
    override val requestMissing: String       = "Anfrage nicht vorhanden."
    override val linkMissing: String          = "Link ungültig."
    override val parentGroupMissing: String   = "Obergruppe nicht vorhanden."
    override val groupInsideItself: String    = "Gruppe in sich selbst."
    override val requestFailed: String        = "Anfrage fehlgeschlagen."
    override val unreadableReply: String      = "Antwort unlesbar."
    override val unreachable: String          = "Server nicht erreichbar."
    override val passwordChanged: String      = "Passwort geändert."

    override def passwordTooShort(min: Int): String =
      s"Passwort braucht $min Zeichen."

    override def noSuchUser(username: String): String =
      s"Niemand heißt \"$username\"."

    override def soleOwner(count: Int): String =
      s"Alleiniger Eigentümer von $count Dingen."

  /** A request signing into one account. */
  private def login(username: String, password: String) = basicRequest
    .post(uri"http://test/api/auth/login")
    .body(Credentials(username, password).asJson.noSpaces)

  /** A request asking who is signed in. */
  private val me = basicRequest.get(uri"http://test/api/auth/me")

  /**
    * What the body of a reply naming nobody looks like. Tapir sends an absent
    * optional body as no body at all, rather than as `null`.
    */
  private val nobody = ""

  /** The session cookie one answer sets, if it set one to anything. */
  private def cookieOf(answer: Response[?]): Option[String] = answer
    .unsafeCookies
    .find(_.name == AuthApi.sessionCookie)
    .map(_.value)
    .filter(_.nonEmpty)

  /**
    * Serves the endpoints of a service over the given store, deriving passwords
    * under the given number of rounds.
    */
  private def hashingAt(users: UserStore, rounds: Int): SendRequest = serve(
    AuthService(
      users,
      AuthPolicy(hashingRounds = rounds),
    ).api,
  )

  /**
    * Runs a check against the endpoints of a service over a fresh database,
    * served in memory rather than over a socket.
    *
    * @param wording
    *   The wording refusals are written in.
    *
    * @param check
    *   The check, given a way to send one request.
    */
  private def served
    (wording: Option[String] => Wording = _ => Wording.english)
    (check: SendRequest => IO[Unit])
    : IO[Unit] = TestDb
    .users(s"auth-service-${ java.util.UUID.randomUUID }")
    .use(users => check(serve(AuthService(users, wording = wording).api)))
