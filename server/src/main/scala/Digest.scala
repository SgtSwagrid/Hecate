package com.alecdorrington.hecate
package server

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
  * The SHA-256 hash, as lower-case hexadecimal, that random secrets such as
  * session tokens and recovery codes are stored as. Unfit for passwords, which
  * need the salted derivation in [[Passwords]].
  */
private[server] object Digest:

  private val hex = HexFormat.of()

  /**
    * The hash the given text is stored as.
    *
    * @param secret
    *   The text to hash.
    *
    * @return
    *   The SHA-256 of the text, as lower-case hexadecimal.
    */
  def of(secret: String): String = hex.formatHex(
    MessageDigest
      .getInstance("SHA-256")
      .digest(secret.getBytes(StandardCharsets.UTF_8)),
  )
