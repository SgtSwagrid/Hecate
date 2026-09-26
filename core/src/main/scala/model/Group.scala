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
  *   top-level group. Withheld, as `None`, from anyone who can see this group
  *   but not its parent.
  *
  * @param public
  *   Whether everyone can find this group and ask to join it, rather than only
  *   the members of the groups it is nested inside.
  */
final case class Group
  (
    id: Long,
    name: String,
    parent: Option[Long] = None,
    public: Boolean = false,
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
  *
  * @param applicants
  *   The users who have asked to join the group and await the owner's answer,
  *   sorted by username. Like invitees, they are not members.
  *
  * @param inviteCode
  *   The secret of the group's invite link, with which anyone who has it may
  *   join, or `None` while the group has no link.
  */
final case class GroupView
  (
    group: Group,
    members: List[User],
    invitees: List[User] = List.empty,
    applicants: List[User] = List.empty,
    inviteCode: Option[String] = None,
  )
  derives Codec.AsObject

/**
  * One group as presented to one of its members: the group, who manages it, and
  * everyone in it, since the members of a group see one another.
  *
  * @param group
  *   The group.
  *
  * @param manager
  *   The owner of the group, who may admit and remove its members. Shown so
  *   that two groups of the same name can be told apart.
  *
  * @param members
  *   The users directly enrolled in the group, the member themselves among
  *   them, sorted by username. As in [[GroupView.members]], users enrolled only
  *   in nested subgroups are not repeated here: they joined those, not this
  *   one.
  */
final case class Membership
  (
    group: Group,
    manager: User,
    members: List[User],
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
  * One group the signed-in user may ask to join, as they see it: a public
  * group, or one nested inside a group they are a member of.
  *
  * @param group
  *   The group. Its parent is withheld unless the user can see that too.
  *
  * @param manager
  *   The owner of the group, who answers a request to join it. Shown so that
  *   two groups of the same name can be told apart.
  *
  * @param requested
  *   Whether the user has asked to join the group already.
  */
final case class Joinable
  (
    group: Group,
    manager: User,
    requested: Boolean,
  )
  derives Codec.AsObject

/**
  * A request to invite one user to a group.
  *
  * @param username
  *   The username of the user to invite.
  */
final case class Invite(username: String) derives Codec.AsObject
