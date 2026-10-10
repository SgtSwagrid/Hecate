package com.alecdorrington.hecate
package model

/**
  * A signed-in user making one request, with the language they asked to be
  * answered in.
  *
  * @param user
  *   The signed-in user.
  *
  * @param locale
  *   The value of the request's `language` cookie (a language code or locale
  *   tag), or `None` if it had none. The host decides what to make of it.
  */
final case class Caller(user: User, locale: Option[String]):

  /** The identifier of the signed-in user. */
  def id: Long = user.id

  /** The username of the signed-in user. */
  def username: String = user.username
