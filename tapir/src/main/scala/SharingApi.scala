package com.alecdorrington.hecate
package tapir

import com.alecdorrington.hecate.model.{Access, Grant, InviteLink, Principal}
import com.alecdorrington.hecate.tapir.Schemas.given
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
  * Endpoints for sharing a resource and its invite link, alike for every kind
  * of resource. Each needs `Own` on it. A resource the caller may not see, or a
  * principal they may not address, is reported as not found; access may be
  * withdrawn from anyone, such as someone who followed the invite link.
  */
object SharingApi:

  /**
    * The sharing endpoints of one kind of resource.
    *
    * @param list
    *   Lists the grants over a resource.
    *
    * @param grant
    *   Replaces whatever access a principal holds over a resource.
    *
    * @param revoke
    *   Withdraws a principal's access over a resource.
    *
    * @param link
    *   Reads a resource's invite link, if any.
    *
    * @param setLink
    *   Makes a resource's invite link, or changes the access it grants.
    *
    * @param relink
    *   Gives a resource's invite link a new code, ending the old.
    *
    * @param unlink
    *   Turns off a resource's invite link.
    */
  final case class Endpoints
    (
      list: AuthApi.Secured[Long, List[Grant]],
      grant: AuthApi.Secured[(Long, Principal, Access), Unit],
      revoke: AuthApi.Secured[(Long, Principal), Unit],
      link: AuthApi.Secured[Long, Option[InviteLink]],
      setLink: AuthApi.Secured[(Long, Access), InviteLink],
      relink: AuthApi.Secured[Long, InviteLink],
      unlink: AuthApi.Secured[Long, Unit],
    )

  /**
    * The sharing endpoints beneath `/api/{segment}/{id}`.
    *
    * @param segment
    *   The path segment of the kind of resource.
    *
    * @return
    *   The endpoints of that kind of resource.
    */
  def of(segment: String): Endpoints =
    val grants     = "api" / segment / path[Long]("id") / "grants"
    val inviteLink = "api" / segment / path[Long]("id") / "invite-link"
    Endpoints(
      AuthApi.secured.get.in(grants).out(jsonBody[List[Grant]]),
      AuthApi.secured.put.in(grants / Principals.path).in(jsonBody[Access]),
      AuthApi.secured.delete.in(grants / Principals.path),
      AuthApi.secured.get.in(inviteLink).out(jsonBody[Option[InviteLink]]),
      AuthApi
        .secured
        .put
        .in(inviteLink)
        .in(jsonBody[Access])
        .out(jsonBody[InviteLink]),
      AuthApi.secured.post.in(inviteLink).out(jsonBody[InviteLink]),
      AuthApi.secured.delete.in(inviteLink),
    )
