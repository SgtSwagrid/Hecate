package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.api.Protocol

/**
  * A session cookie for a response to set: a new session's token, or nothing,
  * to end one. Hosts set
  * [[com.alecdorrington.hecate.api.Protocol.sessionCookie]] exactly as
  * [[header]] says: site-wide, `HttpOnly` and `SameSite=Strict`.
  *
  * @param token
  *   The session's secret token, or empty to clear the cookie.
  *
  * @param maxAge
  *   The number of seconds the browser is to keep the cookie, or `0` to drop
  *   it.
  */
final case class SessionCookie(token: String, maxAge: Long):

  /** The value of the `Set-Cookie` header that sets this cookie. */
  def header: String =
    s"${ Protocol.sessionCookie }=$token; Max-Age=$maxAge; " +
      "Path=/; HttpOnly; SameSite=Strict"

  /** Leaves the secret token out of logs. */
  override def toString: String = s"SessionCookie(<token>, $maxAge)"

object SessionCookie:

  /** A cookie that ends whatever session the browser holds. */
  val closed: SessionCookie = SessionCookie("", 0)
