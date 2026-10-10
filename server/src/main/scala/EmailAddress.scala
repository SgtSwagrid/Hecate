package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.model.AuthRefusal
import java.util.Locale

/**
  * The validation and normal form of email addresses.
  *
  * The check is loose (one `@`, a local part, a domain of two or more labels),
  * as only a confirmation mail proves an address, but it rules out anything
  * that could spill into a mail's headers, such as a line break or a comma.
  */
object EmailAddress:

  /** The characters an address may have anywhere but in its domain's dots. */
  private val character = """[^\s\p{Cntrl}@,;:<>()\[\]\\"]"""

  private val label = s"""(?:(?!\\.)$character)+"""

  private val shape = s"$character+@$label(?:\\.$label)+".r

  /**
    * Normalises an address for storage and comparison.
    *
    * @param address
    *   The address as typed.
    *
    * @return
    *   A copy of the address, trimmed and in lower case.
    */
  def normalise(address: String): String = address.trim.toLowerCase(Locale.ROOT)

  /**
    * Checks text as an email address.
    *
    * @param address
    *   The text to check.
    *
    * @return
    *   A refusal if the text is longer than
    *   [[com.alecdorrington.hecate.api.Protocol.maxEmailLength]] or not shaped
    *   like an address, or `None`.
    */
  def problem(address: String): Option[AuthRefusal] = Bounds
    .email(address)
    .orElse(
      Option.unless(shape.matches(address.trim))(AuthRefusal.EmailInvalid),
    )
