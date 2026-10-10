package com.alecdorrington.hecate

import cats.effect.IO
import com.alecdorrington.hecate.model.AuthRefusal
import scala.concurrent.ExecutionContext
import slick.dbio.DBIO

/** The definitions every store and service here shares. */
package object server:

  /**
    * A service's answer to a request: the result, or the refusal for the host
    * to word. Never failed: an unexpected failure is reported to the host and
    * answered as [[AuthRefusal.Failed]].
    *
    * @tparam X
    *   The type of the result.
    */
  type Answer[X] = IO[Either[AuthRefusal, X]]

  /** Runs Slick's `DBIO` continuations on the thread completing the action. */
  given ExecutionContext = ExecutionContext.parasitic

  /**
    * Runs an action over some values, or answers without asking the database
    * when there are none, as not every database accepts an empty `IN`.
    *
    * @tparam X
    *   The type of the values.
    *
    * @tparam Y
    *   The type of the answer.
    *
    * @param values
    *   The values to run the action over.
    *
    * @param none
    *   The answer when there are no values.
    *
    * @param some
    *   The action to run over the values, which are never empty.
    *
    * @return
    *   An action yielding either answer.
    */
  def ifAny[X, Y]
    (values: Seq[X])
    (none: => Y)
    (some: Seq[X] => DBIO[Y])
    : DBIO[Y] = if values.isEmpty then DBIO.successful(none) else some(values)

  private[server] def required[X]
    (found: Option[X], refusal: AuthRefusal)
    : DBIO[X] =
    found.fold[DBIO[X]](DBIO.failed(AuthProblem(refusal)))(DBIO.successful)

  private[server] def refuseUnless
    (holds: Boolean, refusal: AuthRefusal)
    : DBIO[Unit] =
    if holds then DBIO.unit else DBIO.failed(AuthProblem(refusal))

  /** Answers with the first refusal among the checks, or else runs the answer. */
  private[server] def checked[X]
    (problems: Option[AuthRefusal]*)
    (answer: => Answer[X])
    : Answer[X] = problems
    .flatten
    .headOption
    .fold(answer)(refusal => IO.pure(Left(refusal)))

  extension [X](action: DBIO[X])

    /** This action with its result discarded. */
    def unit: DBIO[Unit] = action.map(_ => ())
