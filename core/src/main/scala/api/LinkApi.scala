package com.alecdorrington.hecate
package api

import com.alecdorrington.hecate.api.Schemas.given
import com.alecdorrington.hecate.model.{InviteCode, LinkPreview, LinkTarget}
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
  * Endpoints for following invite links. A link is known by its code alone (see
  * [[InviteCode]]), and leads to a group, which following it joins, or to a
  * resource, over which following it grants the access the link carries. Links
  * to groups are made by [[GroupApi]], and links to resources by the host
  * application, which alone knows who may share one.
  */
object LinkApi:

  /**
    * The code of an invite link, as a path segment. Bounded, so that nothing
    * long is ever looked up; the case is the reader's to ignore.
    */
  private def code: EndpointInput.PathCapture[String] = path[String]("code")
    .validate(Validator.maxLength(InviteCode.length))

  /**
    * An endpoint that shows where one invite link leads, for whoever opens it
    * to see what they would be agreeing to.
    */
  val preview: AuthApi.Secured[String, LinkPreview] = AuthApi
    .secured
    .get
    .in("api" / "invite-links" / code)
    .out(jsonBody[LinkPreview])

  /**
    * An endpoint that follows one invite link, giving the signed-in user what
    * it leads to and returning that. Following a link whose target they have
    * already changes nothing, and never lowers the access they hold.
    */
  val follow: AuthApi.Secured[String, LinkTarget] = AuthApi
    .secured
    .post
    .in("api" / "invite-links" / code)
    .out(jsonBody[LinkTarget])
