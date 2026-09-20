package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.AuthRefusal

/**
  * How a service answers a failure: worded for its reader when the refusal is
  * theirs to understand, and recorded and answered with the wording's fixed
  * phrase otherwise, so that no driver or SQL detail ever reaches a client.
  * Shared by every service here, so that one failure cannot be reported one way
  * and another a second way.
  *
  * @param wording
  *   The wording refusals are written in, chosen by the language a request asks
  *   for, or `None` when it asks for none.
  *
  * @param report
  *   Records a failure whose detail must not reach the client.
  */
private[server] final class Failures
  (
    wording: Option[String] => Wording,
    report: Throwable => IO[Unit],
  ):

  /**
    * Runs an action for one reader, answering any failure in their language.
    *
    * @param locale
    *   The language the request asked to be answered in.
    *
    * @param action
    *   The action to run.
    *
    * @return
    *   An action yielding what it produced, or why it was refused.
    */
  def attempt[X](locale: Option[String])(action: IO[X]): IO[Either[String, X]] =
    caught(locale)(action.map(Right(_)))

  /**
    * As [[attempt]], for an action that already answers with a refusal of its
    * own, such as a sign-in with the wrong password.
    *
    * @param locale
    *   The language the request asked to be answered in.
    *
    * @param action
    *   The action to run.
    *
    * @return
    *   An action yielding what it produced, or why it was refused.
    */
  def caught[X]
    (locale: Option[String])
    (action: IO[Either[String, X]])
    : IO[Either[String, X]] =
    action.handleErrorWith(explain(locale, _).map(Left(_)))

  /**
    * The message one reader is shown for one failure. An [[AuthProblem]] is
    * worded for them, as its refusal is theirs to understand. Any other failure
    * is handed to [[report]] and answered only with the wording's fixed phrase,
    * as a driver's message can include the failing SQL.
    */
  private def explain(locale: Option[String], error: Throwable): IO[String] =
    error match
      case problem: AuthProblem =>
        IO.pure(wording(locale).phrase(problem.refusal))
      case _ => report(error).as(wording(locale).phrase(AuthRefusal.Failed))
