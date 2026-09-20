package com.alecdorrington.hecate
package server

import cats.effect.IO
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
  * Salted password hashing using PBKDF2, so that passwords are never stored in
  * plain text. Stored hashes take the form `iterations:salt:hash`, with the
  * salt and hash in Base64, so old hashes remain verifiable if the iteration
  * count is later raised.
  *
  * Deriving a hash deliberately costs around a tenth of a second of pinned CPU,
  * so both operations are suspended as blocking work.
  */
object Passwords:

  /**
    * The PBKDF2 iteration count applied to newly hashed passwords where a host
    * application names none, following OWASP's guidance for PBKDF2-HMAC-SHA256.
    * Every hash stores the count it was derived under, so this may be raised
    * whenever the guidance moves without stranding a single stored password.
    */
  val iterations = 600000

  /** The size of the derived hash, in bits. */
  private val keyBits = 256

  /**
    * The most iterations a stored hash may ask for. A hash is a number this
    * process obeys, so a row naming millions of rounds would pin a thread for
    * minutes on end; one naming none at all would fail outright. Only a count
    * between one and this is derived, and any other never matches, exactly as
    * any other malformed hash never matches.
    */
  private val maxRounds = 10000000

  /** The size of the random salt, in bytes. */
  private val saltBytes = 16

  private val random = SecureRandom()

  /**
    * A well-formed hash that no password matches, for checking against when
    * there is no stored hash to check. Verifying against it costs a full
    * derivation, so an unknown username takes as long to refuse as a wrong
    * password and cannot be told apart by timing.
    *
    * Its salt and hash must both stay non-empty: [[verify]] rejects a hash with
    * an empty field immediately, which would defeat the whole point.
    */
  val decoy: String = s"$iterations:${ encode(randomBytes(saltBytes)) }:" +
    encode(randomBytes(keyBits / 8))

  /**
    * Hashes a password under a fresh random salt.
    *
    * @param password
    *   The password to hash.
    *
    * @param rounds
    *   How many iterations to derive it under. Higher costs an attacker more,
    *   and costs this server the same, on every sign-in.
    *
    * @return
    *   A hash in the stored form, of the given password under a fresh salt.
    */
  def hash(password: String, rounds: Int = iterations): IO[String] = IO
    .blocking:
      val salt = randomBytes(saltBytes)
      s"$rounds:${ encode(salt) }:${ encode(derive(password, salt, rounds)) }"

  /**
    * Whether the password matches a stored hash. Hashes are compared in
    * constant time, and a malformed stored hash never matches.
    */
  def verify(password: String, stored: String): IO[Boolean] = IO.blocking:
    stored.split(':') match
      case Array(rounds, salt, hash) =>
        (rounds.toIntOption, decode(salt), decode(hash)) match
          case (Some(count), Some(saltBytes), Some(hashBytes))
            if count > 0 && count <= maxRounds =>
            MessageDigest.isEqual(
              derive(password, saltBytes, count),
              hashBytes,
            )
          case _ => false
      case _ => false

  /**
    * Whether a stored hash was derived under fewer rounds than are applied now,
    * and so is worth deriving again the next time the password is known.
    *
    * @param stored
    *   The stored hash.
    *
    * @param rounds
    *   The iteration count a password would be hashed under today.
    *
    * @return
    *   Whether the stored hash is weaker than a fresh one would be. A hash too
    *   malformed to say is left alone: [[verify]] refuses it anyway.
    */
  def outdated(stored: String, rounds: Int = iterations): Boolean = stored
    .split(':')
    .headOption
    .flatMap(_.toIntOption)
    .exists(_ < rounds)

  /** Derives a hash from a password and salt using PBKDF2. */
  private def derive
    (
      password: String,
      salt: Array[Byte],
      rounds: Int,
    )
    : Array[Byte] = SecretKeyFactory
    .getInstance("PBKDF2WithHmacSHA256")
    .generateSecret(PBEKeySpec(
      password.toCharArray,
      salt,
      rounds,
      keyBits,
    ))
    .getEncoded

  /** A fresh array of the given size, filled with secure random bytes. */
  private def randomBytes(size: Int): Array[Byte] =
    val bytes = new Array[Byte](size)
    random.nextBytes(bytes)
    bytes

  /** Encodes bytes as Base64 text. */
  private def encode(bytes: Array[Byte]): String = Base64
    .getEncoder
    .encodeToString(bytes)

  /** Decodes Base64 text to bytes, or `None` when malformed or empty. */
  private def decode(text: String): Option[Array[Byte]] =
    try Some(Base64.getDecoder.decode(text)).filter(_.nonEmpty)
    catch case _: IllegalArgumentException => None
