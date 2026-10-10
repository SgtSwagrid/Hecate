package com.alecdorrington.hecate
package server

import cats.effect.IO
import java.security.SecureRandom
import java.util.Locale

/**
  * A generator of one-time recovery codes, which regain an account without
  * email or an administrator. A code is ten characters with no look-alikes,
  * shown as two groups of five, about 49 bits of entropy; being random, it is
  * stored as a plain SHA-256 hash.
  */
object RecoveryCode:

  private val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"

  private val length = 10

  /** The number of codes in a fresh set. */
  val setSize = 10

  private val random = SecureRandom()

  /** An effect generating a fresh set of codes, as they are to be written down. */
  val generate: IO[List[String]] = IO(List.fill(setSize)(fresh))

  /**
    * Hashes a code for storage, ignoring spacing, hyphens and letter case.
    *
    * @param code
    *   The code as typed.
    *
    * @return
    *   A hash in the form the code is stored as.
    */
  def hash(code: String): String = Digest.of(normalise(code))

  private def fresh: String =
    val characters =
      List.fill(length)(alphabet(random.nextInt(alphabet.length)))
    s"${ characters.take(length / 2).mkString }-" +
      characters.drop(length / 2).mkString

  private def normalise(code: String): String = code
    .toLowerCase(Locale.ROOT)
    .filter(_.isLetterOrDigit)
