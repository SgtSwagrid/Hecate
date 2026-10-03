package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.AuthRefusal

/**
  * The one way services answer failures: an [[AuthProblem]] with its refusal,
  * and anything else reported and answered as [[AuthRefusal.Failed]], as a
  * driver's message can include the failing SQL.
  *
  * @param report
  *   The handler of failures whose detail must not reach the client.
  */
private[server] final class Failures(report: Throwable => IO[Unit]):

  def attempt[X](action: IO[X]): Answer[X] =
    attemptRefusable(action.map(Right(_)))

  /** As [[attempt]], for an action that may already answer with a refusal. */
  def attemptRefusable[X](action: Answer[X]): Answer[X] = action
    .handleErrorWith(refusal(_).map(Left(_)))

  private def refusal(error: Throwable): IO[AuthRefusal] = error match
    case AuthProblem(refusal) => IO.pure(refusal)
    case _                    => report(error).as(AuthRefusal.Failed)
