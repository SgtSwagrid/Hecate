package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * One user group: a named collection of users, owned by the user who created
  * it. Groups may be nested inside other groups to arbitrary depth, so that a
  * group can stand for a whole hierarchy (e.g. a year containing classes
  * containing study groups).
  *
  * @param id
  *   The unique, stable identifier of this group, assigned by the store.
  *
  * @param name
  *   The display name of this group.
  *
  * @param parent
  *   The identifier of the group this group is nested inside, or `None` for a
  *   top-level group.
  */
final case class Group
  (
    id: Long,
    name: String,
    parent: Option[Long] = None,
  )
  derives Codec.AsObject

/**
  * The content of a new or updated group, without its identifier.
  *
  * @param name
  *   The display name of the group.
  *
  * @param parent
  *   The identifier of the group it is nested inside, if any.
  */
final case class GroupDraft(name: String, parent: Option[Long] = None)
  derives Codec.AsObject

/**
  * One group as presented to its owner: the group itself together with its
  * direct members and the users invited to it. Nesting is reconstructed by the
  * reader from [[Group.parent]], as a flat list survives arbitrary depth
  * without a recursive codec.
  *
  * @param group
  *   The group.
  *
  * @param members
  *   The users directly enrolled in the group, sorted by username. Users
  *   enrolled only in nested subgroups are not repeated here.
  *
  * @param invitees
  *   The users invited to the group who have not yet accepted, sorted by
  *   username. They are not members, and nothing addressed to the group reaches
  *   them.
  */
final case class GroupView
  (
    group: Group,
    members: List[User],
    invitees: List[User] = List.empty,
  )
  derives Codec.AsObject

/**
  * One pending invitation to join a group, as the invited user sees it.
  *
  * @param id
  *   The unique identifier of this invitation, assigned by the store.
  *
  * @param group
  *   The group the invitation is to. Its place in the owner's hierarchy is
  *   withheld, as it is none of the invitee's business.
  *
  * @param inviter
  *   The owner of the group, who sent the invitation. Shown so that the invitee
  *   knows who is asking before they consent.
  */
final case class Invitation(id: Long, group: Group, inviter: User)
  derives Codec.AsObject

/**
  * A request to invite one user to a group.
  *
  * @param username
  *   The username of the user to invite.
  */
final case class Invite(username: String) derives Codec.AsObject
