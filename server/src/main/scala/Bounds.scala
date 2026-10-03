package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.api.Protocol
import com.alecdorrington.hecate.model.AuthRefusal

/**
  * The length limits [[Protocol]] sets on request text, checked by every
  * service before the text is stored, hashed or compared.
  */
private[server] object Bounds:

  def name(text: String): Option[AuthRefusal] =
    tooLong(text, Protocol.maxNameLength)

  def guestName(text: String): Option[AuthRefusal] =
    tooLong(text, Protocol.maxGuestNameLength)

  def password(text: String): Option[AuthRefusal] =
    tooLong(text, Protocol.maxPasswordLength)

  def email(text: String): Option[AuthRefusal] =
    tooLong(text, Protocol.maxEmailLength)

  private def tooLong(text: String, max: Int): Option[AuthRefusal] = Option
    .when(text.length > max)(AuthRefusal.TooLong(max))
