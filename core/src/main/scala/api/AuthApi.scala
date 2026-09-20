package com.alecdorrington.hecate
package api

import com.alecdorrington.hecate.api.Schemas.given
import com.alecdorrington.hecate.model.{
  AuthRules, Credentials, PasswordChange, PasswordCheck, Recovery,
  RecoveryCodes, User,
}
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
  * Endpoints for account registration and sign-in. A successful registration or
  * sign-in opens a session identified by a secret token in an HTTP-only cookie,
  * which endpoints built on [[secured]] then require.
  *
  * Every request may also carry a `language` cookie naming the language its
  * reader wants refusals in. A client sets it itself, for its whole origin, so
  * that every request carries it with no further effort; the server passes its
  * value on to the host application's wording, and never reads it otherwise.
  *
  * These are endpoint *descriptions* only, shared by the server that implements
  * them and any client that calls them. The implementations live in the server
  * half of this library.
  */
object AuthApi:

  /**
    * The most characters a username may have. Long enough for any name a person
    * would choose, and short enough that nothing unbounded is ever stored or
    * compared.
    */
  val maxUsernameLength = Schemas.maxNameLength

  /**
    * The most characters a password may have. Deriving a hash costs time in
    * proportion to the password's length, unauthenticated and on a pinned
    * thread, so a password no person would type is refused at the edge rather
    * than hashed. Long enough for any passphrase, and for any password a
    * manager would generate.
    */
  val maxPasswordLength = Schemas.maxPasswordLength

  /** The name of the cookie that holds the session token. */
  val sessionCookie = "auth_session"

  /** The name of the cookie that names the language refusals are worded in. */
  val languageCookie = "language"

  /**
    * What every secured endpoint reads before anything else: the session
    * cookie, if any, and the language cookie, if any.
    */
  type Security = (Option[String], Option[String])

  /**
    * The shape of every endpoint here that requires a signed-in user: its
    * security logic answers a caller, its inputs and outputs vary, and it is
    * refused with a sentence.
    *
    * @tparam I
    *   What the request carries.
    *
    * @tparam O
    *   What the reply carries.
    */
  type Secured[I, O] = Endpoint[Security, I, String, O, Any]

  /**
    * The shape of every endpoint here that anybody may call, refused with a
    * sentence in the same way.
    *
    * @tparam I
    *   What the request carries.
    *
    * @tparam O
    *   What the reply carries.
    */
  type Open[I, O] = PublicEndpoint[I, String, O, Any]

  /** The language cookie, as an input of an endpoint that is not secured. */
  private def language = cookie[Option[String]](languageCookie)

  /**
    * The base of every endpoint that requires a signed-in user, in this library
    * and in the host application alike. Its security logic resolves the session
    * cookie to a [[com.alecdorrington.hecate.model.Caller]], or explains why it
    * cannot, in the language the language cookie asks for.
    */
  val secured: Secured[Unit, Unit] = endpoint
    .securityIn(cookie[Option[String]](sessionCookie))
    .securityIn(language)
    .errorOut(stringBody)

  /**
    * An endpoint that registers a new account and signs it in, setting the
    * session cookie.
    */
  val register
    : Open[
      (Credentials, Option[String]),
      (User, CookieValueWithMeta),
    ] = endpoint
    .post
    .in("api" / "auth" / "register")
    .in(jsonBody[Credentials])
    .in(language)
    .out(jsonBody[User])
    .out(setCookie(sessionCookie))
    .errorOut(stringBody)

  /**
    * An endpoint that signs into an existing account, setting the session
    * cookie.
    */
  val login
    : Open[
      (Credentials, Option[String]),
      (User, CookieValueWithMeta),
    ] = endpoint
    .post
    .in("api" / "auth" / "login")
    .in(jsonBody[Credentials])
    .in(language)
    .out(jsonBody[User])
    .out(setCookie(sessionCookie))
    .errorOut(stringBody)

  /**
    * An endpoint that signs the current user out, closing their session and
    * clearing the session cookie.
    */
  val logout: Open[Option[String], CookieValueWithMeta] = endpoint
    .post
    .in("api" / "auth" / "logout")
    .in(cookie[Option[String]](sessionCookie))
    .out(setCookie(sessionCookie))
    .errorOut(stringBody)

  /** An endpoint that identifies the signed-in user, if any. */
  val me: Open[Option[String], Option[User]] = endpoint
    .get
    .in("api" / "auth" / "me")
    .in(cookie[Option[String]](sessionCookie))
    .out(jsonBody[Option[User]])
    .errorOut(stringBody)

  /**
    * An endpoint that describes the rules for accounts, so that a client can
    * state them before a request is refused.
    */
  val rules: Open[Unit, AuthRules] = endpoint
    .get
    .in("api" / "auth" / "rules")
    .out(jsonBody[AuthRules])
    .errorOut(stringBody)

  /**
    * An endpoint that changes the signed-in user's password, given their
    * current one. Signs out every other session, and replaces this one's cookie
    * with a fresh session.
    */
  val changePassword: Secured[PasswordChange, CookieValueWithMeta] = secured
    .put
    .in("api" / "auth" / "password")
    .in(jsonBody[PasswordChange])
    .out(setCookie(sessionCookie))

  /**
    * An endpoint that issues the signed-in user a fresh set of recovery codes,
    * given their password, invalidating every earlier code. The codes are
    * returned this once, and never again.
    */
  val recoveryCodes: Secured[PasswordCheck, RecoveryCodes] = secured
    .post
    .in("api" / "auth" / "recovery-codes")
    .in(jsonBody[PasswordCheck])
    .out(jsonBody[RecoveryCodes])

  /** An endpoint that counts the signed-in user's unused recovery codes. */
  val recoveryCodesLeft: Secured[Unit, Int] = secured
    .get
    .in("api" / "auth" / "recovery-codes")
    .out(jsonBody[Int])

  /**
    * An endpoint that regains an account whose password has been forgotten,
    * using one of its recovery codes: sets a new password, signs out every
    * other session, and signs in.
    */
  val recover
    : Open[
      (Recovery, Option[String]),
      (User, CookieValueWithMeta),
    ] = endpoint
    .post
    .in("api" / "auth" / "recover")
    .in(jsonBody[Recovery])
    .in(language)
    .out(jsonBody[User])
    .out(setCookie(sessionCookie))
    .errorOut(stringBody)

  /**
    * An endpoint that deletes the signed-in user's account and everything that
    * belongs to it, given their password, and clears the session cookie.
    */
  val deleteAccount: Secured[PasswordCheck, CookieValueWithMeta] = secured
    .post
    .in("api" / "auth" / "account" / "delete")
    .in(jsonBody[PasswordCheck])
    .out(setCookie(sessionCookie))
