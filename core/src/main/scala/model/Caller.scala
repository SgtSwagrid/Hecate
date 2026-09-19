package com.alecdorrington.hecate
package model

/**
  * A signed-in user making one request, with the language they asked to be
  * answered in. The security logic behind every secured endpoint resolves each
  * request to one of these, so that every refusal can be worded for its reader.
  *
  * @param user
  *   The signed-in user.
  *
  * @param locale
  *   The language the request asked for, as the `language` cookie's value (a
  *   language code or locale tag), or `None` when the request named none. What
  *   to make of it is the host application's decision.
  */
final case class Caller(user: User, locale: Option[String]):

  /** The identifier of the signed-in user. */
  def id: Long = user.id

  /** The username of the signed-in user. */
  def username: String = user.username
