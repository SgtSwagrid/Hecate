package com.alecdorrington.hecate
package tapir

import com.alecdorrington.hecate.api.Protocol.sessionCookie
import com.alecdorrington.hecate.model.{
  Guest, InviteCode, LinkPreview, LinkTarget, Welcome,
}
import com.alecdorrington.hecate.tapir.Schemas.given
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
  * Descriptions of the endpoints for following invite links. A link is known by
  * its code (see [[InviteCode]]) and leads to a group, which following joins,
  * or to a resource, over which following grants the link's access. Links to
  * groups are made through [[GroupApi]], and links to resources by the host.
  */
object LinkApi:

  private def code: EndpointInput.PathCapture[String] = path[String]("code")
    .validate(Validator.maxLength(InviteCode.length))

  /** An endpoint that shows where an invite link leads, before it is followed. */
  val preview: AuthApi.Secured[String, LinkPreview] = AuthApi
    .secured
    .get
    .in("api" / "invite-links" / code)
    .out(jsonBody[LinkPreview])

  /**
    * An endpoint that follows an invite link for the signed-in user, returning
    * its target. It never lowers the access they hold.
    */
  val follow: AuthApi.Secured[String, LinkTarget] = AuthApi
    .secured
    .post
    .in("api" / "invite-links" / code / "follow")
    .out(jsonBody[LinkTarget])

  /**
    * An endpoint that follows an invite link as a new guest, made under the
    * name they give, and signs them in. No guest is made if the link leads
    * nowhere.
    */
  val welcome
    : AuthApi.Open[
      (String, Guest, Option[String]),
      (Welcome, CookieValueWithMeta),
    ] = AuthApi
    .base
    .post
    .in("api" / "invite-links" / code / "welcome")
    .in(jsonBody[Guest])
    .in(AuthApi.language)
    .out(jsonBody[Welcome])
    .out(setCookie(sessionCookie))
