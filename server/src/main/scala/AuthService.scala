package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.hecate.api.AuthApi
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  AuthRefusal, AuthRules, Caller, Credentials, PasswordChange, Recovery,
  RecoveryCodes, User,
}
import java.security.SecureRandom
import java.util.Base64
import sttp.model.headers.Cookie.SameSite
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.server.ServerEndpoint

/**
  * The implementation of the API endpoints specified in [[AuthApi]], which
  * register users, manage their cookie-based sign-in sessions, change and
  * recover their passwords, and delete their accounts. Also resolves session
  * tokens to users on behalf of every secured endpoint, this library's and the
  * host application's alike.
  *
  * Every refusal is worded for its reader: the language cookie each request
  * carries is handed to [[wording]], and the [[Wording]] that comes back writes
  * the sentence. The library itself decides only *why* a request is refused.
  *
  * @param users
  *   The store the users, sessions and recovery codes are kept in.
  *
  * @param policy
  *   How long sessions last, and what makes a password acceptable.
  *
  * @param accounts
  *   The store that deletes accounts, or `None` to offer no account deletion. A
  *   host application should give one only once its own cascade can remove
  *   everything of its that belongs to a user; until then the endpoint is not
  *   served at all, and [[AuthRules.accountDeletion]] tells clients so.
  *
  * @param wording
  *   The wording refusals are written in, chosen by the language a request asks
  *   for, or `None` when it asks for none. Defaults to the library's English
  *   for every request.
  */
final class AuthService
  (
    users: UserStore,
    policy: AuthPolicy = AuthPolicy(),
    accounts: Option[AccountStore] = None,
    wording: Option[String] => Wording = _ => Wording.english,
  ):

  /** An endpoint that registers a new account and signs it in. */
  lazy val register: ServerEndpoint[Any, IO] = AuthApi
    .register
    .serverLogic(signUp)

  /** An endpoint that signs into an existing account. */
  lazy val login: ServerEndpoint[Any, IO] = AuthApi.login.serverLogic(signIn)

  /** An endpoint that signs the current user out. */
  lazy val logout: ServerEndpoint[Any, IO] = AuthApi.logout.serverLogic(signOut)

  /** An endpoint that identifies the signed-in user, if any. */
  lazy val me: ServerEndpoint[Any, IO] = AuthApi.me.serverLogic(current)

  /** An endpoint that describes the rules for accounts. */
  lazy val rules: ServerEndpoint[Any, IO] = AuthApi
    .rules
    .serverLogic(_ =>
      IO.pure(Right(AuthRules(
        policy.minPasswordLength,
        accounts.isDefined,
      ))),
    )

  /** An endpoint that changes the signed-in user's password. */
  lazy val changePassword: ServerEndpoint[Any, IO] = AuthApi
    .changePassword
    .serverSecurityLogic(require)
    .serverLogic(caller => change => changePasswordOf(caller, change))

  /** An endpoint that issues the signed-in user a fresh set of recovery codes. */
  lazy val recoveryCodes: ServerEndpoint[Any, IO] = AuthApi
    .recoveryCodes
    .serverSecurityLogic(require)
    .serverLogic(caller =>
      check => confirmed(caller, check.password)(issueCodes(caller.user)),
    )

  /** An endpoint that counts the signed-in user's unused recovery codes. */
  lazy val recoveryCodesLeft: ServerEndpoint[Any, IO] = AuthApi
    .recoveryCodesLeft
    .serverSecurityLogic(require)
    .serverLogic(caller =>
      _ => users.recoveryCodesLeft(caller.id).map(Right(_)),
    )

  /** An endpoint that regains an account with a recovery code. */
  lazy val recover: ServerEndpoint[Any, IO] = AuthApi
    .recover
    .serverLogic(recoverAccount)

  /**
    * An endpoint that deletes the signed-in user's account. A deletion the
    * store refuses, as for the only owner of something, is reported with the
    * store's reason, and deletes nothing.
    */
  lazy val deleteAccount: ServerEndpoint[Any, IO] = AuthApi
    .deleteAccount
    .serverSecurityLogic(require)
    .serverLogic(caller =>
      check =>
        accounts match
          case None        => IO.pure(Left(words(caller).noAccountDeletion))
          case Some(store) => confirmed(caller, check.password)(
              store
                .delete(caller.id)
                .attemptNarrow[AuthProblem]
                .map(_.bimap(
                  problem => words(caller).phrase(problem.refusal),
                  _ => AuthService.cookie("", 0),
                )),
            ).map(_.flatten),
    )

  /** Every endpoint implemented by this service. */
  lazy val api: List[ServerEndpoint[Any, IO]] = List(
    register,
    login,
    logout,
    me,
    rules,
    changePassword,
    recoveryCodes,
    recoveryCodesLeft,
    recover,
  ) ++ accounts.as(deleteAccount)

  /**
    * Resolves a session token to its signed-in user, as the caller of one
    * request in the language it asked for. The security logic behind every
    * endpoint built on [[AuthApi.secured]].
    */
  def require(security: AuthApi.Security): IO[Either[String, Caller]] =
    val (token, locale) = security
    token
      .flatTraverse(users.sessionUser)
      .map(_.map(Caller(_, locale)).toRight(wording(locale).signedOut))

  /** The wording one caller's refusals are written in. */
  private def words(caller: Caller): Wording = wording(caller.locale)

  /** Registers a new account, then opens a session for it. */
  private def signUp
    (request: (Credentials, Option[String]))
    : IO[Either[String, (User, CookieValueWithMeta)]] =
    val (credentials, locale) = request
    policy.reject(credentials) match
      case Some(problem) => IO.pure(Left(wording(locale).phrase(problem)))
      case None          => Passwords
          .hash(credentials.password)
          .flatMap(users.register(credentials.username.trim, _))
          .flatMap:
            case None       => IO.pure(Left(wording(locale).usernameTaken))
            case Some(user) => openSession(user).map(Right(_))

  /**
    * Checks the credentials against the stored hash, then opens a session. An
    * unknown username is checked against [[Passwords.decoy]], so that the reply
    * takes as long either way and cannot be used to enumerate accounts.
    */
  private def signIn
    (request: (Credentials, Option[String]))
    : IO[Either[String, (User, CookieValueWithMeta)]] =
    val (credentials, locale) = request
    users
      .findByUsername(credentials.username.trim)
      .flatMap(verified(_, credentials.password))
      .flatMap(_.traverse(row => openSession(row.toUser)))
      .map(_.toRight(wording(locale).incorrectCredentials))

  /** Closes any session under the given token, and expires the cookie. */
  private def signOut
    (token: Option[String])
    : IO[Either[String, CookieValueWithMeta]] = token
    .traverse_(users.closeSession)
    .as(Right(AuthService.cookie("", 0)))

  /** The signed-in user under the given token, if any. */
  private def current(token: Option[String]): IO[Either[String, Option[User]]] =
    token.flatTraverse(users.sessionUser).map(Right(_))

  /**
    * Changes a user's password once their current one is confirmed. Every
    * session they had is closed, this one included, and a fresh session opened
    * in its place, so that anyone who knew the old password is signed out.
    */
  private def changePasswordOf
    (caller: Caller, change: PasswordChange)
    : IO[Either[String, CookieValueWithMeta]] =
    policy.rejectPassword(change.replacement) match
      case Some(problem) => IO.pure(Left(words(caller).phrase(problem)))
      case None          => confirmed(caller, change.current):
          for
            hash    <- Passwords.hash(change.replacement)
            token   <- AuthService.freshToken
            expires <- expiry
            _       <- users.resetPassword(caller.id, hash, token, expires)
          yield AuthService.cookie(token, policy.sessionSeconds)

  /** Generates a fresh set of recovery codes, storing only their hashes. */
  private def issueCodes(user: User): IO[RecoveryCodes] = RecoveryCode
    .generate
    .flatTap(codes =>
      users.replaceRecoveryCodes(user.id, codes.map(RecoveryCode.hash)),
    )
    .map(RecoveryCodes(_))

  /**
    * Regains an account with a recovery code, resetting its password and
    * signing in. The new password is hashed before the account is even looked
    * up, so that the reply takes as long whether or not the account exists.
    */
  private def recoverAccount
    (request: (Recovery, Option[String]))
    : IO[Either[String, (User, CookieValueWithMeta)]] =
    val (recovery, locale) = request
    policy.rejectPassword(recovery.replacement) match
      case Some(problem) => IO.pure(Left(wording(locale).phrase(problem)))
      case None          =>
        for
          hash    <- Passwords.hash(recovery.replacement)
          token   <- AuthService.freshToken
          expires <- expiry
          found   <- users.recover(
            recovery.username.trim,
            RecoveryCode.hash(recovery.code),
            hash,
            token,
            expires,
          )
        yield found
          .map(user => (user, AuthService.cookie(token, policy.sessionSeconds)))
          .toRight(wording(locale).incorrectRecovery)

  /**
    * Runs an action on behalf of a signed-in user once they have given their
    * password again, as a consequential request demands.
    */
  private def confirmed[X]
    (caller: Caller, password: String)
    (action: => IO[X])
    : IO[Either[String, X]] = users
    .findById(caller.id)
    .flatMap(verified(_, password))
    .flatMap:
      case Some(_) => action.map(Right(_))
      case None    => IO.pure(Left(words(caller).incorrectPassword))

  /**
    * The found account, provided the password matches its hash. An absent
    * account is checked against [[Passwords.decoy]], so that the reply takes as
    * long either way and cannot be used to enumerate accounts.
    */
  private def verified
    (found: Option[UserRow], password: String)
    : IO[Option[UserRow]] = Passwords
    .verify(
      password,
      found.fold(Passwords.decoy)(_.passwordHash),
    )
    .map(matches => found.filter(_ => matches))

  /** Opens a fresh session for the given user, yielding its cookie. */
  private def openSession(user: User): IO[(User, CookieValueWithMeta)] =
    for
      token   <- AuthService.freshToken
      expires <- expiry
      _       <- users.openSession(token, user.id, expires)
      _       <- users.purgeExpired
    yield (user, AuthService.cookie(token, policy.sessionSeconds))

  /** When a session opened now expires, in epoch milliseconds. */
  private def expiry: IO[Long] = IO
    .realTime
    .map(_.toMillis + policy.sessionMillis)

object AuthService:

  private val random = SecureRandom()

  /** Generates a fresh unguessable session token. */
  private val freshToken: IO[String] = IO:
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /**
    * A session cookie holding the given token for the given lifetime. Withheld
    * from scripts and from cross-site requests, and cleared by reusing this
    * with an empty token and no lifetime.
    */
  private def cookie(token: String, seconds: Long): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value = token,
      maxAge = Some(seconds),
      path = Some("/"),
      httpOnly = true,
      sameSite = Some(SameSite.Strict),
    )

/**
  * How an application wants its sign-ins to behave.
  *
  * @param sessionSeconds
  *   How long a session lasts before the user must sign in again. Enforced both
  *   in the browser's cookie and in the session store.
  *
  * @param minPasswordLength
  *   The fewest characters a password may have.
  */
final case class AuthPolicy
  (
    sessionSeconds: Long = 30L * 24 * 60 * 60,
    minPasswordLength: Int = 8,
  ):

  /** How long a session lasts, in milliseconds. */
  def sessionMillis: Long = sessionSeconds * 1000

  /** Why these credentials are unusable for a new account, if they are. */
  def reject(credentials: Credentials): Option[AuthRefusal] =
    if credentials.username.trim.isEmpty then Some(AuthRefusal.EmptyUsername)
    else rejectPassword(credentials.password)

  /** Why this password is unusable, if it is. */
  def rejectPassword(password: String): Option[AuthRefusal] = Option.when(
    password.length < minPasswordLength,
  )(AuthRefusal.PasswordTooShort(minPasswordLength))
