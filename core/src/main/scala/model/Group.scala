package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * A named group of users, which may nest inside another group to any depth.
  * Who manages it is decided by grants over it as a resource
  * ([[Resource.group]]), its creator first holding [[Access.Own]].
  *
  * @param id
  *   The identifier of the group, assigned by the store.
  *
  * @param name
  *   The display name of the group.
  *
  * @param parentId
  *   The identifier of the group this one nests inside, or `None` for a
  *   top-level group. Also `None` for a reader who cannot see the parent.
  *
  * @param public
  *   Whether anyone may find the group and ask to join it, not only the members
  *   of the groups enclosing it.
  */
final case class Group
  (
    id: Long,
    name: String,
    parentId: Option[Long] = None,
    public: Boolean = false,
  )
  derives Codec.AsObject

/**
  * The content of a new or updated group.
  *
  * @param name
  *   The display name of the group.
  *
  * @param parentId
  *   The identifier of the group it nests inside, or `None` for a top-level
  *   group.
  */
final case class GroupDetails
  (
    name: String,
    parentId: Option[Long] = None,
  )
  derives Codec.AsObject

/**
  * A principal holding access, as it is shown to someone who may not be able to
  * address it.
  *
  * @param principal
  *   The user, group or system.
  *
  * @param name
  *   The user's username or the group's name, or `None` for the system, which
  *   the host names.
  */
final case class Holder
  (
    principal: Principal,
    name: Option[String],
  )
  derives Codec.AsObject

/**
  * A group as someone who manages it sees it: anyone holding at least
  * [[Access.View]] over it. Nesting is given by [[Group.parentId]] alone.
  *
  * @param group
  *   The group.
  *
  * @param access
  *   The access the reader holds over the group: [[Access.View]] to see who is
  *   in it, [[Access.Edit]] to change that and the group itself, and
  *   [[Access.Own]] to delete it and choose who manages it.
  *
  * @param members
  *   The users directly in the group, sorted by username. Users only in groups
  *   nested inside it are not included.
  *
  * @param invitees
  *   The users invited who have not yet accepted, sorted by username. They are
  *   not members.
  *
  * @param applicants
  *   The users who have asked to join and await an answer, sorted by username.
  *   They are not members.
  *
  * @param inviteCode
  *   The code of the group's invite link, or `None` if it has none or the
  *   reader may not change who is in the group.
  */
final case class ManagedGroup
  (
    group: Group,
    access: Access,
    members: List[User],
    invitees: List[User] = List.empty,
    applicants: List[User] = List.empty,
    inviteCode: Option[String] = None,
  )
  derives Codec.AsObject

/**
  * A group as one of its members sees it.
  *
  * @param group
  *   The group.
  *
  * @param owners
  *   Whoever holds [[Access.Own]] over the group.
  *
  * @param members
  *   The users directly in the group, the reader among them, sorted by
  *   username.
  *
  * @param enclosing
  *   The groups enclosing it, outermost first, which the reader belongs to
  *   through it, without their members.
  */
final case class Membership
  (
    group: Group,
    owners: List[Holder],
    members: List[User],
    enclosing: List[Group] = List.empty,
  )
  derives Codec.AsObject

/**
  * A pending invitation to join a group, as the invited user sees it.
  *
  * @param id
  *   The identifier of the invitation, assigned by the store.
  *
  * @param group
  *   The group invited to, with its parent withheld.
  *
  * @param inviter
  *   The user who sent the invitation, or `None` if their account is gone.
  */
final case class Invitation
  (
    id: Long,
    group: Group,
    inviter: Option[User],
  )
  derives Codec.AsObject

/**
  * A group the signed-in user may ask to join: a public group, or one nested
  * inside a group they are a member of.
  *
  * @param group
  *   The group, with its parent withheld unless the user can see it.
  *
  * @param owners
  *   Whoever holds [[Access.Own]] over the group.
  *
  * @param requested
  *   Whether the user has already asked to join.
  */
final case class JoinableGroup
  (
    group: Group,
    owners: List[Holder],
    requested: Boolean,
  )
  derives Codec.AsObject

/**
  * A user to invite to a group, named by their username.
  *
  * @param username
  *   The username of the user to invite.
  */
final case class Invitee(username: String) derives Codec.AsObject
