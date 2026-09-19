package com.alecdorrington.hecate
package api

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

  private given Schema[User]           = Schema.derived
  private given Schema[Credentials]    = Schema.derived
  private given Schema[AuthRules]      = Schema.derived
  private given Schema[PasswordChange] = Schema.derived
  private given Schema[PasswordCheck]  = Schema.derived
  private given Schema[Recovery]       = Schema.derived
  private given Schema[RecoveryCodes]  = Schema.derived

  /** The name of the cookie that holds the session token. */
  val sessionCookie = "auth_session"

  /** The name of the cookie that names the language refusals are worded in. */
  val languageCookie = "language"

  /**
    * What every secured endpoint reads before anything else: the session
    * cookie, if any, and the language cookie, if any.
    */
  type Security = (Option[String], Option[String])

  /** The language cookie, as an input of an endpoint that is not secured. */
  private def language = cookie[Option[String]](languageCookie)

  /**
    * The base of every endpoint that requires a signed-in user, in this library
    * and in the host application alike. Its security logic resolves the session
    * cookie to a [[com.alecdorrington.hecate.model.Caller]], or explains why it
    * cannot, in the language the language cookie asks for.
    */
  val secured: Endpoint[Security, Unit, String, Unit, Any] = endpoint
    .securityIn(cookie[Option[String]](sessionCookie))
    .securityIn(language)
    .errorOut(stringBody)

  /**
    * An endpoint that registers a new account and signs it in, setting the
    * session cookie.
    */
  val register
    : PublicEndpoint[
      (Credentials, Option[String]),
      String,
      (User, CookieValueWithMeta),
      Any,
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
    : PublicEndpoint[
      (Credentials, Option[String]),
      String,
      (User, CookieValueWithMeta),
      Any,
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
  val logout
    : PublicEndpoint[
      Option[String],
      String,
      CookieValueWithMeta,
      Any,
    ] = endpoint
    .post
    .in("api" / "auth" / "logout")
    .in(cookie[Option[String]](sessionCookie))
    .out(setCookie(sessionCookie))
    .errorOut(stringBody)

  /** An endpoint that identifies the signed-in user, if any. */
  val me
    : PublicEndpoint[
      Option[String],
      String,
      Option[User],
      Any,
    ] = endpoint
    .get
    .in("api" / "auth" / "me")
    .in(cookie[Option[String]](sessionCookie))
    .out(jsonBody[Option[User]])
    .errorOut(stringBody)

  /**
    * An endpoint that describes the rules for accounts, so that a client can
    * state them before a request is refused.
    */
  val rules: PublicEndpoint[Unit, String, AuthRules, Any] = endpoint
    .get
    .in("api" / "auth" / "rules")
    .out(jsonBody[AuthRules])
    .errorOut(stringBody)

  /**
    * An endpoint that changes the signed-in user's password, given their
    * current one. Signs out every other session, and replaces this one's cookie
    * with a fresh session.
    */
  val changePassword
    : Endpoint[
      Security,
      PasswordChange,
      String,
      CookieValueWithMeta,
      Any,
    ] = secured
    .put
    .in("api" / "auth" / "password")
    .in(jsonBody[PasswordChange])
    .out(setCookie(sessionCookie))

  /**
    * An endpoint that issues the signed-in user a fresh set of recovery codes,
    * given their password, invalidating every earlier code. The codes are
    * returned this once, and never again.
    */
  val recoveryCodes
    : Endpoint[
      Security,
      PasswordCheck,
      String,
      RecoveryCodes,
      Any,
    ] = secured
    .post
    .in("api" / "auth" / "recovery-codes")
    .in(jsonBody[PasswordCheck])
    .out(jsonBody[RecoveryCodes])

  /** An endpoint that counts the signed-in user's unused recovery codes. */
  val recoveryCodesLeft: Endpoint[Security, Unit, String, Int, Any] = secured
    .get
    .in("api" / "auth" / "recovery-codes")
    .out(jsonBody[Int])

  /**
    * An endpoint that regains an account whose password has been forgotten,
    * using one of its recovery codes: sets a new password, signs out every
    * other session, and signs in.
    */
  val recover
    : PublicEndpoint[
      (Recovery, Option[String]),
      String,
      (User, CookieValueWithMeta),
      Any,
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
  val deleteAccount
    : Endpoint[
      Security,
      PasswordCheck,
      String,
      CookieValueWithMeta,
      Any,
    ] = secured
    .post
    .in("api" / "auth" / "account" / "delete")
    .in(jsonBody[PasswordCheck])
    .out(setCookie(sessionCookie))
