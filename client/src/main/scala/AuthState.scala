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

  /**
    * Everything here is bound to the page rather than to a view, since this
    * state outlives any one view and every request it makes must arrive whether
    * or not something is on screen to receive it.
    */
  private given Owner = unsafeWindowOwner

  private val userVar: Var[Option[User]] = Var(None)

  private val readyVar: Var[Boolean] = Var(false)

  private val errorVar: Var[Option[String]] = Var(None)

  private val noticeVar: Var[Option[String]] = Var(None)

  private val pendingVar: Var[Boolean] = Var(false)

  private val rulesVar: Var[Option[AuthRules]] = Var(None)

  private val recoveryCodesVar: Var[Option[List[String]]] = Var(None)

  private val recoveryCodesLeftVar: Var[Option[Int]] = Var(None)

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
  val user: Signal[Option[User]] = userVar.signal

  /**
    * Whether the startup probe has answered. Until it has, nothing is known
    * about the user, and a `None` [[user]] must not be read as "signed out".
    */
  val ready: Signal[Boolean] = readyVar.signal

  /** The reason the last request about the account was refused, if any. */
  val error: Signal[Option[String]] = errorVar.signal

  /**
    * A confirmation that the last request about the account succeeded, for
    * requests whose success is not otherwise visible, such as a password
    * change.
    */
  val notice: Signal[Option[String]] = noticeVar.signal

  /** Whether a request about the account is in flight. */
  val pending: Signal[Boolean] = pendingVar.signal

  /**
    * The server's rules for accounts, once fetched, so that a form can state
    * them before a request is refused.
    */
  val rules: Signal[Option[AuthRules]] = rulesVar.signal

  /**
    * A set of recovery codes just generated, which the server will never show
    * again, until [[dismissRecoveryCodes]] is called once they are written
    * down.
    */
  val recoveryCodes: Signal[Option[List[String]]] = recoveryCodesVar.signal

  /** How many unused recovery codes the signed-in user has, once known. */
  val recoveryCodesLeft: Signal[Option[Int]] = recoveryCodesLeftVar.signal

  // The state lives as long as the page, so it probes here rather than relying
  // on a view to remember to bind the request.
  locally:
    probe(0)
    Fetch
      .get("/api/auth/rules")
      .text
      .map(response => decoded[AuthRules](response))
      .recover { case _ => Some(None) }
      .foreach(rulesVar.set)
    // The count is the signed-in user's; with nobody signed in there is nothing
    // to ask for, and asking would only be refused.
    user
      .changes
      .foreach: found =>
        recoveryCodesVar.set(None)
        if found.isDefined then refreshCodesLeft()
        else recoveryCodesLeftVar.set(None)

  /**
    * Asks the server who is signed in, and adopts its answer, retrying a few
    * times when the request never arrives.
    *
    * Only a definite answer settles it. A request that fails to reach the
    * server says nothing about who is signed in, and adopting its silence would
    * sign a signed-in user out, and show them a sign-in form, over one dropped
    * request at startup. After [[AuthState.attempts]] such silences the server
    * is taken to be unreachable and nobody is taken to be signed in, so that a
    * page is never left waiting forever on a server that will not answer.
    *
    * @param attempt
    *   How many times the probe has already gone unanswered.
    */
  private def probe(attempt: Int): Unit = Fetch
    .get("/api/auth/me")
    .text
    .map(signedIn)
    .recover { case _ => Some(None) }
    .foreach:
      case Some(found)                          => settle(found)
      case None if attempt < AuthState.attempts => reprobe(attempt)
      case None                                 => settle(None)

  /** Adopts the server's answer, and reports that the probe has answered. */
  private def settle(found: Option[User]): Unit =
    userVar.set(found)
    readyVar.set(true)

  /** Probes again, once the wait between attempts has passed. */
  private def reprobe(attempt: Int): Unit = EventStream
    .fromValue(())
    .delay(AuthState.retryMillis)
    .foreach(_ => probe(attempt + 1))

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
    errorVar.set(None)
    noticeVar.set(None)
    Fetch
      .post("/api/auth/logout")
      .text
      .map(_ => ())
      .recover { case _ => Some(()) }
      .foreach(_ => userVar.set(None))

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
  )(_ => noticeVar.set(Some(wording.passwordChanged)))

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
      case None        => errorVar.set(Some(wording.unreadableReply))
      case Some(codes) =>
        recoveryCodesVar.set(Some(codes.codes))
        recoveryCodesLeftVar.set(Some(codes.codes.size))

  /** Forgets the recovery codes just shown, once they have been written down. */
  def dismissRecoveryCodes(): Unit = recoveryCodesVar.set(None)

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
  )(_ => userVar.set(None))

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
    Fetch
      .get("/api/auth/me")
      .text
      .map(signedIn)
      .recover { case _ => Some(None) }
      .foreach: answer =>
        rechecking = false
        answer.foreach(userVar.set)

  /**
    * Asks the server again who is signed in and how many recovery codes they
    * have left, as when told the account changed elsewhere: a session of theirs
    * was closed, or their password or recovery codes were replaced. A user
    * still signed in is left as they were, so that nothing following them, such
    * as recovery codes just generated and not yet written down, is disturbed;
    * anyone else is adopted, as by [[recheck]].
    */
  def refresh(): Unit = Fetch
    .get("/api/auth/me")
    .text
    .map(signedIn)
    .recover { case _ => Some(None) }
    .foreach:
      case Some(found) if found.map(_.id) == userVar.now().map(_.id) =>
        if found.isDefined then refreshCodesLeft()
      case Some(found) => userVar.set(found)
      case None        => ()

  /** Discards the last error and notice, so that a fresh form starts clean. */
  def clearError(): Unit =
    errorVar.set(None)
    noticeVar.set(None)

  /** Refetches how many unused recovery codes the signed-in user has. */
  private def refreshCodesLeft(): Unit = Fetch
    .get("/api/auth/recovery-codes")
    .text
    .map(response => decoded[Int](response))
    .recover { case _ => Some(None) }
    .foreach(recoveryCodesLeftVar.set)

  /** Runs a request that signs in, adopting the user it replies with. */
  private def signInWith(request: EventStream[FetchResponse[String]]): Unit =
    perform(request): response =>
      decoded[User](response) match
        case None       => errorVar.set(Some(wording.unreadableReply))
        case Some(user) => userVar.set(Some(user))

  /**
    * Runs one request about the account, tracking that it is in flight, and
    * handing a successful response on, or recording why it was refused.
    */
  private def perform
    (request: EventStream[FetchResponse[String]])
    (succeeded: FetchResponse[String] => Unit)
    : Unit =
    pendingVar.set(true)
    errorVar.set(None)
    noticeVar.set(None)
    Replied
      .of(request)
      .foreach: outcome =>
        pendingVar.set(false)
        outcome match
          case Replied.Answered(response) => succeeded(response)
          case Replied.Refused(problem)   => errorVar.set(Some(problem))
          case Replied.Unreachable(_) => errorVar.set(Some(wording.unreachable))

  /**
    * Who a reply to `/api/auth/me` says is signed in, or `None` when it says
    * nothing, as when it was refused or cannot be read. Nobody signed in is an
    * empty body, not `null`, which is how an absent value is sent, and would
    * otherwise read as saying nothing, so that a session ended elsewhere was
    * never noticed.
    */
  private def signedIn(response: FetchResponse[String]): Option[Option[User]] =
    if response.status < 400 && response.data.isBlank then Some(None)
    else decoded[Option[User]](response)

  /** Decodes a successful JSON response, or `None` when it cannot be read. */
  private def decoded[X : Decoder](response: FetchResponse[String]): Option[X] =
    Option
      .when(response.status < 400)(decode[X](response.data).toOption)
      .flatten

object AuthState:

  /**
    * How many times a startup probe that never arrives is sent again before the
    * server is taken to be unreachable.
    */
  private val attempts = 3

  /** How long to wait between probes, in milliseconds. */
  private val retryMillis = 1000
