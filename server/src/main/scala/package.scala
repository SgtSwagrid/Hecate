package com.alecdorrington.hecate

import scala.concurrent.ExecutionContext

/**
  * Slick's `DBIO.map` and `DBIO.flatMap` need an `ExecutionContext` to run
  * their continuations on. The work is trivial, so it runs on the thread that
  * completes the action rather than hopping to a pool.
  */
package object server:

  given ExecutionContext = ExecutionContext.parasitic
