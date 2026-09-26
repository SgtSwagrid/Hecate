package com.alecdorrington.hecate
package api

import com.alecdorrington.hecate.api.Schemas.given
import com.alecdorrington.hecate.model.{
  Group, GroupDraft, GroupView, Invitation, Invite, Joinable, Membership, User,
}
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
  * Endpoints for managing user groups. Groups belong to the signed-in user who
  * created them, and may be nested inside one another to arbitrary depth via
  * [[Group.parent]]. Nobody joins a group without consenting: its owner may
  * join it at once, and anyone else joins by accepting an invitation, by having
  * a request to join admitted, or by following an invite link.
  */
object GroupApi:

  /**
    * The most characters a group's name may have, so that nothing unbounded is
    * stored. A username is bounded by [[AuthApi.maxUsernameLength]], which is
    * the same length.
    */
  val maxNameLength = Schemas.maxNameLength

  /**
    * An endpoint that lists every group owned by the signed-in user, with their
    * members. Nesting is reconstructed by the reader from [[Group.parent]].
    */
  val list: AuthApi.Secured[Unit, List[GroupView]] = AuthApi
    .secured
    .get
    .in("api" / "groups")
    .out(jsonBody[List[GroupView]])

  /**
    * An endpoint that lists the groups the signed-in user is a member of,
    * whoever owns them, so that being enrolled is at least visible to the
    * person enrolled, each with its manager and everyone in it, as the members
    * of a group see one another.
    */
  val mine: AuthApi.Secured[Unit, List[Membership]] = AuthApi
    .secured
    .get
    .in("api" / "groups" / "mine")
    .out(jsonBody[List[Membership]])

  /** An endpoint that withdraws the signed-in user from one group. */
  val leave: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .delete
    .in("api" / "groups" / path[Long]("group") / "membership")

  /**
    * An endpoint that makes the signed-in user a member of one group they own,
    * as whoever manages a group needs nobody's leave to join it. Joining a
    * group they are in already changes nothing.
    */
  val join: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .put
    .in("api" / "groups" / path[Long]("group") / "membership")

  /**
    * An endpoint that lists the groups the signed-in user may ask to join:
    * every public group, and every group nested inside one they are a member
    * of, but none they own, belong to or are invited to. Any group they have
    * asked to join is listed as well, whether or not they could still find it,
    * so that they can always withdraw the request.
    */
  val joinable: AuthApi.Secured[Unit, List[Joinable]] = AuthApi
    .secured
    .get
    .in("api" / "groups" / "joinable")
    .out(jsonBody[List[Joinable]])

  /**
    * An endpoint that asks to join one group the signed-in user may see, for
    * its owner to admit or decline. Asking twice changes nothing; asking to
    * join a group they are invited to accepts the invitation, and asking to
    * join one they own joins it, as either way both sides have now agreed.
    */
  val request: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .put
    .in("api" / "groups" / path[Long]("group") / "request")

  /**
    * An endpoint that withdraws the signed-in user's request to join one group.
    * Withdrawing a request they never made changes nothing.
    */
  val retract: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .delete
    .in("api" / "groups" / path[Long]("group") / "request")

  /**
    * An endpoint that stores a new group for the signed-in user, returning the
    * group with its assigned identifier.
    */
  val create: AuthApi.Secured[GroupDraft, Group] = AuthApi
    .secured
    .post
    .in("api" / "groups")
    .in(jsonBody[GroupDraft])
    .out(jsonBody[Group])

  /**
    * An endpoint that renames and/or moves one stored group. A move that would
    * nest a group inside itself is refused.
    */
  val update: AuthApi.Secured[(Long, GroupDraft), Unit] = AuthApi
    .secured
    .put
    .in("api" / "groups" / path[Long]("group"))
    .in(jsonBody[GroupDraft])

  /**
    * An endpoint that deletes one stored group, together with every group
    * nested beneath it.
    */
  val delete: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .delete
    .in("api" / "groups" / path[Long]("group"))

  /**
    * An endpoint that makes one group public, so that everyone can find it and
    * ask to join, or private again, so that only the members of the groups it
    * is nested inside can.
    */
  val publish: AuthApi.Secured[(Long, Boolean), Unit] = AuthApi
    .secured
    .put
    .in("api" / "groups" / path[Long]("group") / "public")
    .in(jsonBody[Boolean])

  /**
    * An endpoint that gives one group an invite link unless it has one,
    * returning the link's code either way. Anyone with the link may join the
    * group; see [[LinkApi]].
    */
  val link: AuthApi.Secured[Long, String] = AuthApi
    .secured
    .put
    .in("api" / "groups" / path[Long]("group") / "invite-link")
    .out(jsonBody[String])

  /**
    * An endpoint that replaces one group's invite link with a new one,
    * returning its code. The old link stops working.
    */
  val relink: AuthApi.Secured[Long, String] = AuthApi
    .secured
    .post
    .in("api" / "groups" / path[Long]("group") / "invite-link")
    .out(jsonBody[String])

  /** An endpoint that turns off one group's invite link. */
  val unlink: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .delete
    .in("api" / "groups" / path[Long]("group") / "invite-link")

  /**
    * An endpoint that invites one user, named by username, to one group,
    * returning the invited user. Inviting someone already a member, or already
    * invited, changes nothing; inviting someone who has asked to join admits
    * them, and inviting oneself joins, as either way both sides have agreed.
    */
  val invite: AuthApi.Secured[(Long, Invite), User] = AuthApi
    .secured
    .post
    .in("api" / "groups" / path[Long]("group") / "invitations")
    .in(jsonBody[Invite])
    .out(jsonBody[User])

  /**
    * An endpoint that admits to one group a user who has asked to join it,
    * making them a member. Nobody who has not asked can be admitted, so that
    * nobody joins without consenting; admitting a member changes nothing.
    */
  val admit: AuthApi.Secured[(Long, Long), Unit] = AuthApi
    .secured
    .put
    .in("api" / "groups" / path[Long]("group") / "members" / path[Long]("user"))

  /**
    * An endpoint that removes one user from one group: a member, an invitee
    * whose invitation is cancelled, or an applicant whose request is declined.
    */
  val withdraw: AuthApi.Secured[(Long, Long), Unit] = AuthApi
    .secured
    .delete
    .in("api" / "groups" / path[Long]("group") / "members" / path[Long]("user"))

  /** An endpoint that lists the pending invitations sent to the signed-in user. */
  val invitations: AuthApi.Secured[Unit, List[Invitation]] = AuthApi
    .secured
    .get
    .in("api" / "invitations")
    .out(jsonBody[List[Invitation]])

  /**
    * An endpoint that accepts one of the signed-in user's invitations, making
    * them a member of its group.
    */
  val accept: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .post
    .in("api" / "invitations" / path[Long]("invitation") / "accept")

  /**
    * An endpoint that declines one of the signed-in user's invitations,
    * deleting it. The owner may invite them again.
    */
  val decline: AuthApi.Secured[Long, Unit] = AuthApi
    .secured
    .post
    .in("api" / "invitations" / path[Long]("invitation") / "decline")
