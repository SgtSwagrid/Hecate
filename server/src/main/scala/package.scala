package com.alecdorrington.hecate

import com.alecdorrington.hecate.model.AuthRefusal
import scala.concurrent.ExecutionContext
import slick.dbio.DBIO

/** What every store here shares. */
package object server:

  /**
    * Slick's `DBIO.map` and `DBIO.flatMap` need an `ExecutionContext` to run
    * their continuations on. The work is trivial, so it runs on the thread that
    * completes the action rather than hopping to a pool.
    */
  given ExecutionContext = ExecutionContext.parasitic

  /**
    * The given action over the given values, or the given answer where there
    * are none, in which case the database is not asked at all: a query over an
    * empty set has an answer already known here, and not every database will
    * even accept one.
    *
    * @param values
    *   What the action is to be run over.
    *
    * @param none
    *   The answer where there are no values.
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

  /** The found value, or a failed transaction refusing for the given reason. */
  private[server] def required[X]
    (found: Option[X], problem: AuthRefusal)
    : DBIO[X] =
    found.fold[DBIO[X]](DBIO.failed(AuthProblem(problem)))(DBIO.successful)

  /**
    * An action doing nothing if the check holds, or else a failed transaction
    * refusing for the given reason.
    */
  private[server] def refuseUnless
    (holds: Boolean, problem: AuthRefusal)
    : DBIO[Unit] =
    if holds then DBIO.successful(()) else DBIO.failed(AuthProblem(problem))

  extension [X](action: DBIO[X])

    /**
      * This action, with whatever it produced discarded: the row counts that
      * every write answers with, which nothing here ever reads.
      */
    def unit: DBIO[Unit] = action.map(_ => ())
