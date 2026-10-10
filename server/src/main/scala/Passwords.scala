package com.alecdorrington.hecate
package server

import cats.effect.IO
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
  * A salted PBKDF2 password hasher. Hashes are stored as
  * `iterations:salt:hash`, salt and hash in Base64, so raising the iteration
  * count strands no stored hash. A derivation takes around a tenth of a second
  * of CPU, so both operations run as blocking work.
  */
object Passwords:

  /**
    * The default PBKDF2 iteration count for [[AuthPolicy]], following OWASP's
    * guidance for PBKDF2-HMAC-SHA256.
    */
  val defaultIterations = 600000

  private val keyBits = 256

  /**
    * The most iterations a stored hash may ask for; any higher count never
    * matches, so a tampered row cannot pin a thread for minutes.
    */
  private val maxIterations = 10000000

  private val saltSize = 16

  private val random = SecureRandom()

  /**
    * Makes a hash that no password matches, to verify against when a username
    * is unknown, so that its refusal takes as long as a wrong password's.
    *
    * Its salt and hash must stay non-empty: [[verify]] rejects an empty field
    * at once, which would reopen the timing channel.
    *
    * @param iterations
    *   The iteration count real passwords are hashed under.
    *
    * @return
    *   A hash in the stored form, under a fresh salt.
    */
  def decoy(iterations: Int): String =
    s"$iterations:${ encode(randomBytes(saltSize)) }:" +
      encode(randomBytes(keyBits / 8))

  /**
    * Hashes a password under a fresh random salt.
    *
    * @param password
    *   The password to hash.
    *
    * @param iterations
    *   The iteration count to derive it under.
    *
    * @return
    *   An effect producing the hash in the stored form.
    */
  def hash(password: String, iterations: Int): IO[String] = IO.blocking:
    val salt = randomBytes(saltSize)
    s"$iterations:${ encode(salt) }:" +
      encode(derive(password, salt, iterations))

  /**
    * Checks a password against a stored hash in constant time.
    *
    * @param password
    *   The password to check.
    *
    * @param stored
    *   The hash in the stored form.
    *
    * @return
    *   An effect producing whether the password matches; a malformed hash never
    *   matches.
    */
  def verify(password: String, stored: String): IO[Boolean] = IO.blocking:
    stored.split(':') match
      case Array(iterations, salt, hash) =>
        (iterations.toIntOption, decode(salt), decode(hash)) match
          case (Some(count), Some(saltBytes), Some(hashBytes))
            if count > 0 && count <= maxIterations =>
            MessageDigest.isEqual(
              derive(password, saltBytes, count),
              hashBytes,
            )
          case _ => false
      case _ => false

  /**
    * Checks whether a stored hash used fewer iterations than the current
    * policy, and so should be derived again when the password is next known.
    *
    * @param stored
    *   The hash in the stored form.
    *
    * @param iterations
    *   The iteration count the current policy hashes under.
    *
    * @return
    *   Whether the hash is weaker than a fresh one; `false` for a malformed
    *   hash.
    */
  def outdated(stored: String, iterations: Int): Boolean = stored
    .split(':')
    .headOption
    .flatMap(_.toIntOption)
    .exists(_ < iterations)

  private def derive
    (
      password: String,
      salt: Array[Byte],
      iterations: Int,
    )
    : Array[Byte] = SecretKeyFactory
    .getInstance("PBKDF2WithHmacSHA256")
    .generateSecret(PBEKeySpec(
      password.toCharArray,
      salt,
      iterations,
      keyBits,
    ))
    .getEncoded

  private def randomBytes(size: Int): Array[Byte] =
    val bytes = new Array[Byte](size)
    random.nextBytes(bytes)
    bytes

  private def encode(bytes: Array[Byte]): String = Base64
    .getEncoder
    .encodeToString(bytes)

  /** Decodes Base64 text, or `None` when malformed or empty. */
  private def decode(text: String): Option[Array[Byte]] =
    try Some(Base64.getDecoder.decode(text)).filter(_.nonEmpty)
    catch case _: IllegalArgumentException => None
