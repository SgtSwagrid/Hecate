package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  AuthRules, Credentials, PasswordChange, PasswordCheck, Recovery,
  RecoveryCodes, User,
}
import com.raquo.laminar.api.L.*
import io.circe.Decoder
import io.circe.parser.decode
import io.laminext.fetch.circe.*
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * The browser-side account state, driving the endpoints of
  * [[com.alecdorrington.hecate.api.AuthApi]]. The session itself lives in an
  * HTTP-only cookie that the browser attaches to every same-origin request, so
  * this holds only who the user is (discovered once on startup, and updated on
  * each sign-in and sign-out) and the state of requests about their account.
  *
  * Headless by design: it exposes signals and commands, and the host
  * application owns all rendering.
  *
  * @param wording
  *   What this state says itself, in the language the host shows. The server's
  *   refusals arrive already worded, in the language of the `language` cookie
  *   the host sets.
  */
final class AuthState(wording: Wording = Wording.english):

  private val currentUser: Var[Option[User]] = Var(None)

  private val probed: Var[Boolean] = Var(false)

  private val failure: Var[Option[String]] = Var(None)

  private val success: Var[Option[String]] = Var(None)

  private val busy: Var[Boolean] = Var(false)

  private val serverRules: Var[Option[AuthRules]] = Var(None)

  private val freshCodes: Var[Option[List[String]]] = Var(None)

  private val codesLeft: Var[Option[Int]] = Var(None)

  /**
    * Whether a [[recheck]] is waiting on the server. Mutable rather than a
    * signal, as nothing observes it: it only keeps a burst of refused requests
    * from each asking the same question.
    */
  private var rechecking = false

  /**
    * The signed-in user, or `None` while nobody is signed in. Its `changes`
    * fire once the startup probe answers and on every subsequent sign-in and
    * sign-out, so that other state can refetch whatever belongs to the user.
    */
  val user: Signal[Option[User]] = currentUser.signal

  /**
    * Whether the startup probe has answered. Until it has, nothing is known
    * about the user, and a `None` [[user]] must not be read as "signed out".
    */
  val ready: Signal[Boolean] = probed.signal

  /** The reason the last request about the account was refused, if any. */
  val error: Signal[Option[String]] = failure.signal

  /**
    * A confirmation that the last request about the account succeeded, for
    * requests whose success is not otherwise visible, such as a password
    * change.
    */
  val notice: Signal[Option[String]] = success.signal

  /** Whether a request about the account is in flight. */
  val pending: Signal[Boolean] = busy.signal

  /**
    * The server's rules for accounts, once fetched, so that a form can state
    * them before a request is refused.
    */
  val rules: Signal[Option[AuthRules]] = serverRules.signal

  /**
    * A set of recovery codes just generated, which the server will never show
    * again, until [[dismissRecoveryCodes]] is called once they are written
    * down.
    */
  val recoveryCodes: Signal[Option[List[String]]] = freshCodes.signal

  /** How many unused recovery codes the signed-in user has, once known. */
  val recoveryCodesLeft: Signal[Option[Int]] = codesLeft.signal

  // The state lives as long as the page, so it probes here rather than relying
  // on a view to remember to bind the request.
  locally:
    given Owner = unsafeWindowOwner
    Fetch
      .get("/api/auth/me")
      .text
      .map(response => decoded[Option[User]](response).flatten)
      .recover { case _ => Some(None) }
      .foreach: found =>
        currentUser.set(found)
        probed.set(true)
    Fetch
      .get("/api/auth/rules")
      .text
      .map(response => decoded[AuthRules](response))
      .recover { case _ => Some(None) }
      .foreach(serverRules.set)
    // The count is the signed-in user's; with nobody signed in there is nothing
    // to ask for, and asking would only be refused.
    user
      .changes
      .foreach: found =>
        freshCodes.set(None)
        if found.isDefined then refreshCodesLeft() else codesLeft.set(None)

  /** Registers a new account under the given credentials, and signs in. */
  def register(username: String, password: String): Unit = signInWith(
    Fetch
      .post(
        "/api/auth/register",
        body = Credentials(username, password),
      )
      .text,
  )

  /** Signs in under the given credentials. */
  def signIn(username: String, password: String): Unit = signInWith(
    Fetch
      .post(
        "/api/auth/login",
        body = Credentials(username, password),
      )
      .text,
  )

  /**
    * Regains an account whose password has been forgotten, with one of its
    * recovery codes, setting a new password and signing in.
    */
  def recover
    (
      username: String,
      code: String,
      replacement: String,
    )
    : Unit = signInWith(
    Fetch
      .post(
        "/api/auth/recover",
        body = Recovery(username, code, replacement),
      )
      .text,
  )

  /** Signs out, closing the session on the server. */
  def signOut(): Unit =
    given Owner = unsafeWindowOwner
    failure.set(None)
    success.set(None)
    Fetch
      .post("/api/auth/logout")
      .text
      .map(_ => ())
      .recover { case _ => Some(()) }
      .foreach(_ => currentUser.set(None))

  /**
    * Changes the signed-in user's password, given their current one. Every
    * other session they had is signed out.
    */
  def changePassword(current: String, replacement: String): Unit = perform(
    Fetch
      .put(
        "/api/auth/password",
        body = PasswordChange(current, replacement),
      )
      .text,
  )(_ => success.set(Some(wording.passwordChanged)))

  /**
    * Generates a fresh set of recovery codes, given the signed-in user's
    * password, invalidating every earlier code. The new codes appear in
    * [[recoveryCodes]].
    */
  def generateRecoveryCodes(password: String): Unit = perform(
    Fetch
      .post(
        "/api/auth/recovery-codes",
        body = PasswordCheck(password),
      )
      .text,
  ): response =>
    decoded[RecoveryCodes](response) match
      case None        => failure.set(Some(wording.unreadableReply))
      case Some(codes) =>
        freshCodes.set(Some(codes.codes))
        codesLeft.set(Some(codes.codes.size))

  /** Forgets the recovery codes just shown, once they have been written down. */
  def dismissRecoveryCodes(): Unit = freshCodes.set(None)

  /**
    * Deletes the signed-in user's account and everything that belongs to it,
    * given their password, then signs them out.
    */
  def deleteAccount(password: String): Unit = perform(
    Fetch
      .post(
        "/api/auth/account/delete",
        body = PasswordCheck(password),
      )
      .text,
  )(_ => currentUser.set(None))

  /**
    * Asks the server again who is signed in, and adopts its answer. For a host
    * application to call when a request that needs a signed-in user has been
    * refused: every refusal from such an endpoint looks alike, whether the
    * session has expired or the user simply may not do that, and this tells the
    * two apart. An expired session signs the user out; anything else leaves
    * them as they were.
    *
    * Only a definite answer is adopted. A request that fails to reach the
    * server leaves the user as they were, so that a dropped connection is never
    * mistaken for being signed out.
    *
    * Calls made while an earlier one is still waiting are dropped, as they ask
    * the same question and that answer is no older than their own would be.
    */
  def recheck(): Unit = if !rechecking then
    rechecking = true
    given Owner = unsafeWindowOwner
    Fetch
      .get("/api/auth/me")
      .text
      .map(response => decoded[Option[User]](response))
      .recover { case _ => Some(None) }
      .foreach: answer =>
        rechecking = false
        answer.foreach(currentUser.set)

  /** Discards the last error and notice, so that a fresh form starts clean. */
  def clearError(): Unit =
    failure.set(None)
    success.set(None)

  /** Refetches how many unused recovery codes the signed-in user has. */
  private def refreshCodesLeft(): Unit =
    given Owner = unsafeWindowOwner
    Fetch
      .get("/api/auth/recovery-codes")
      .text
      .map(response => decoded[Int](response))
      .recover { case _ => Some(None) }
      .foreach(codesLeft.set)

  /** Runs a request that signs in, adopting the user it replies with. */
  private def signInWith(request: EventStream[FetchResponse[String]]): Unit =
    perform(request): response =>
      decoded[User](response) match
        case None       => failure.set(Some(wording.unreadableReply))
        case Some(user) => currentUser.set(Some(user))

  /**
    * Runs one request about the account, tracking that it is in flight, and
    * handing a successful response on, or recording why it was refused.
    */
  private def perform
    (request: EventStream[FetchResponse[String]])
    (succeeded: FetchResponse[String] => Unit)
    : Unit =
    given Owner = unsafeWindowOwner
    busy.set(true)
    failure.set(None)
    success.set(None)
    request
      .map(response =>
        Either.cond(
          response.status < 400,
          response,
          response.data,
        ),
      )
      .recover { case _ => Some(Left(wording.unreachable)) }
      .foreach: outcome =>
        busy.set(false)
        outcome.fold(
          problem => failure.set(Some(problem)),
          succeeded,
        )

  /** Decodes a successful JSON response, or `None` when it cannot be read. */
  private def decoded[X : Decoder](response: FetchResponse[String]): Option[X] =
    Option
      .when(response.status < 400)(decode[X](response.data).toOption)
      .flatten
