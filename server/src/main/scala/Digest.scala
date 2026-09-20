package com.alecdorrington.hecate
package server

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
  * The hash a secret that this library did not choose for a person is stored
  * as: SHA-256, as lower-case hexadecimal text.
  *
  * Session tokens and recovery codes are hashed with this, and passwords are
  * not. A password is chosen by a person, so a stolen hash of one can be
  * attacked with a dictionary, and it needs the slow, salted derivation in
  * [[Passwords]]. These are drawn at random from enough entropy that no such
  * dictionary can exist, so a plain digest is enough, and cheap enough to
  * afford on every request that carries one.
  */
private[server] object Digest:

  /**
    * The hash the given text is stored as.
    *
    * @param secret
    *   The text to hash.
    *
    * @return
    *   The SHA-256 of the text, as lower-case hexadecimal.
    */
  def of(secret: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(secret.getBytes(StandardCharsets.UTF_8))
    .map(byte => f"$byte%02x")
    .mkString
