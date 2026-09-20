package com.alecdorrington.hecate

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

  extension [X](action: DBIO[X])

    /**
      * This action, with whatever it produced discarded: the row counts that
      * every write answers with, which nothing here ever reads.
      */
    def unit: DBIO[Unit] = action.map(_ => ())
