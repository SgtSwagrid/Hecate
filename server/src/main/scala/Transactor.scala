package com.alecdorrington.hecate
package server

import cats.effect.IO
import slick.dbio.DBIO

/**
  * A handle to the host's database, through which the stores run their queries;
  * this library opens no connection of its own.
  */
trait Transactor:

  /**
    * Runs a database action.
    *
    * @tparam X
    *   The type of the action's result.
    *
    * @param action
    *   The action to run.
    *
    * @return
    *   An effect producing the action's result.
    */
  def run[X](action: DBIO[X]): IO[X]
