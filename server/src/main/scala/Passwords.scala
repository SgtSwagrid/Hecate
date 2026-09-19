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

  /** The PBKDF2 iteration count applied to newly hashed passwords. */
  private val iterations = 120000

  /** The size of the derived hash, in bits. */
  private val keyBits = 256

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

  /** Hashes a password under a fresh random salt. */
  def hash(password: String): IO[String] = IO.blocking:
    val salt = randomBytes(saltBytes)
    s"$iterations:${ encode(salt) }:${ encode(
        derive(password, salt, iterations),
      ) }"

  /**
    * Whether the password matches a stored hash. Hashes are compared in
    * constant time, and a malformed stored hash never matches.
    */
  def verify(password: String, stored: String): IO[Boolean] = IO.blocking:
    stored.split(':') match
      case Array(rounds, salt, hash) =>
        (rounds.toIntOption, decode(salt), decode(hash)) match
          case (Some(count), Some(saltBytes), Some(hashBytes)) => MessageDigest
              .isEqual(
                derive(password, saltBytes, count),
                hashBytes,
              )
          case _ => false
      case _ => false

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
