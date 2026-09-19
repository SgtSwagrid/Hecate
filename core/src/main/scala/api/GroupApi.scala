package com.alecdorrington.hecate
package api

import com.alecdorrington.hecate.model.{
  Group, GroupDraft, GroupView, Invitation, Invite, User,
}
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
  * Endpoints for managing user groups. Groups belong to the signed-in user who
  * created them, and may be nested inside one another to arbitrary depth via
  * [[Group.parent]]. Users join by accepting an invitation, never by being
  * added directly.
  */
object GroupApi:

  private given Schema[User]       = Schema.derived
  private given Schema[Group]      = Schema.derived
  private given Schema[GroupView]  = Schema.derived
  private given Schema[GroupDraft] = Schema.derived
  private given Schema[Invitation] = Schema.derived
  private given Schema[Invite]     = Schema.derived

  /**
    * An endpoint that lists every group owned by the signed-in user, with their
    * members. Nesting is reconstructed by the reader from [[Group.parent]].
    */
  val list
    : Endpoint[
      AuthApi.Security,
      Unit,
      String,
      List[GroupView],
      Any,
    ] = AuthApi.secured.get.in("api" / "groups").out(jsonBody[List[GroupView]])

  /**
    * An endpoint that lists the groups the signed-in user is a member of,
    * whoever owns them, so that being enrolled is at least visible to the
    * person enrolled.
    */
  val mine
    : Endpoint[
      AuthApi.Security,
      Unit,
      String,
      List[Group],
      Any,
    ] = AuthApi
    .secured
    .get
    .in("api" / "groups" / "mine")
    .out(jsonBody[List[Group]])

  /** An endpoint that withdraws the signed-in user from one group. */
  val leave: Endpoint[AuthApi.Security, Long, String, Unit, Any] = AuthApi
    .secured
    .delete
    .in("api" / "groups" / path[Long]("group") / "membership")

  /**
    * An endpoint that stores a new group for the signed-in user, returning the
    * group with its assigned identifier.
    */
  val create
    : Endpoint[
      AuthApi.Security,
      GroupDraft,
      String,
      Group,
      Any,
    ] = AuthApi
    .secured
    .post
    .in("api" / "groups")
    .in(jsonBody[GroupDraft])
    .out(jsonBody[Group])

  /**
    * An endpoint that renames and/or moves one stored group. A move that would
    * nest a group inside itself is refused.
    */
  val update
    : Endpoint[
      AuthApi.Security,
      (Long, GroupDraft),
      String,
      Unit,
      Any,
    ] = AuthApi
    .secured
    .put
    .in("api" / "groups" / path[Long]("group"))
    .in(jsonBody[GroupDraft])

  /**
    * An endpoint that deletes one stored group, together with every group
    * nested beneath it.
    */
  val delete: Endpoint[AuthApi.Security, Long, String, Unit, Any] = AuthApi
    .secured
    .delete
    .in("api" / "groups" / path[Long]("group"))

  /**
    * An endpoint that invites one user, named by username, to one group,
    * returning the invited user. Inviting someone already a member, or already
    * invited, changes nothing.
    */
  val invite
    : Endpoint[
      AuthApi.Security,
      (Long, Invite),
      String,
      User,
      Any,
    ] = AuthApi
    .secured
    .post
    .in("api" / "groups" / path[Long]("group") / "invitations")
    .in(jsonBody[Invite])
    .out(jsonBody[User])

  /**
    * An endpoint that removes one user from one group: a member, or an invitee
    * whose invitation is cancelled.
    */
  val withdraw
    : Endpoint[
      AuthApi.Security,
      (Long, Long),
      String,
      Unit,
      Any,
    ] = AuthApi
    .secured
    .delete
    .in("api" / "groups" / path[Long]("group") / "members" / path[Long]("user"))

  /** An endpoint that lists the pending invitations sent to the signed-in user. */
  val invitations
    : Endpoint[
      AuthApi.Security,
      Unit,
      String,
      List[Invitation],
      Any,
    ] = AuthApi
    .secured
    .get
    .in("api" / "invitations")
    .out(jsonBody[List[Invitation]])

  /**
    * An endpoint that accepts one of the signed-in user's invitations, making
    * them a member of its group.
    */
  val accept: Endpoint[AuthApi.Security, Long, String, Unit, Any] = AuthApi
    .secured
    .post
    .in("api" / "invitations" / path[Long]("invitation") / "accept")

  /**
    * An endpoint that declines one of the signed-in user's invitations,
    * deleting it. The owner may invite them again.
    */
  val decline: Endpoint[AuthApi.Security, Long, String, Unit, Any] = AuthApi
    .secured
    .post
    .in("api" / "invitations" / path[Long]("invitation") / "decline")
