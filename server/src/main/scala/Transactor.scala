package com.alecdorrington.hecate
package server

import cats.effect.IO
import slick.dbio.DBIO

/**
  * A handle to the database the auth stores read and write, supplied by the
  * host application so that this library neither opens nor owns a connection.
  * Applications that already wrap Slick in [[IO]] can simply extend this.
  */
trait Transactor:

  /** Runs a database action, suspending its effects in [[IO]]. */
  def run[X](action: DBIO[X]): IO[X]
