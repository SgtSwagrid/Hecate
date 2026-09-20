package com.alecdorrington.hecate
package server

import cats.effect.IO
import java.security.SecureRandom

/**
  * One-time recovery codes, which regain an account whose password has been
  * forgotten, without email or an administrator.
  *
  * A code is ten characters from an alphabet without look-alikes (no `0`/`o`,
  * `1`/`i`/`l`), shown as two groups of five; about 49 bits of entropy each, so
  * that guessing one is hopeless without any rate limit. Codes are stored only
  * as SHA-256 hashes. Unlike passwords, they need no slow, salted hash: they
  * are random rather than chosen by a person, so no dictionary of likely codes
  * exists to try against a stolen hash.
  */
object RecoveryCode:

  /** The characters a code is made from: lower case, with no look-alikes. */
  private val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"

  /** The number of characters in a code, not counting its separator. */
  private val length = 10

  /** The number of codes in a fresh set. */
  val setSize = 10

  private val random = SecureRandom()

  /** Generates a fresh set of codes, in the form they are to be written down. */
  val generate: IO[List[String]] = IO(List.fill(setSize)(fresh))

  /**
    * The hash a code is stored as. Spacing, hyphens and letter case are
    * ignored, so that a code is accepted however it was copied out.
    */
  def hash(code: String): String = Digest.of(normalise(code))

  /** One fresh code, as two hyphenated groups of five. */
  private def fresh: String =
    val chars = List.fill(length)(alphabet(random.nextInt(alphabet.length)))
    s"${ chars.take(length / 2).mkString }-${ chars.drop(length / 2).mkString }"

  /** A code reduced to its characters alone, in lower case. */
  private def normalise(code: String): String = code
    .toLowerCase
    .filter(_.isLetterOrDigit)
