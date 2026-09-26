package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.model.Resource

/**
  * Whom a change the library has just made concerns, reported to the host once
  * the change is committed, so that the host can let them know, over a socket
  * say, that what they are shown of it may be out of date. Nothing here says
  * what changed: whoever is told should ask again, through the endpoints that
  * check what they may see, so that telling someone can never disclose anything
  * to them.
  */
enum Affected:

  /**
    * Groups changed: their names, nesting or visibility, their invite links, or
    * who is in them, invited to them or asking to join them. What reaches the
    * people in them may have changed too, as a membership confers every grant
    * to the group.
    *
    * @param people
    *   Everyone whose view of any of the groups may have changed.
    *
    * @param groups
    *   The groups whose members may have changed, with every group enclosing
    *   them, which their members belong to through them. Anything the host
    *   addressed to one of these may now reach other people.
    */
  case Groups(people: Audience, groups: Set[Long])

  /**
    * The grants over one resource changed, as when someone followed an invite
    * link to it. The host alone knows who may see its resources, and so whom
    * this concerns.
    */
  case Grants(resource: Resource)

  /**
    * One person's account changed: a session of theirs was closed, their
    * password or recovery codes were replaced, or the account was deleted.
    */
  case Account(user: Long)

/** Whom a change concerns. */
enum Audience:

  /** Everyone, as when a group that anyone may find changed. */
  case Everyone

  /** The given people alone, identified as users. */
  case People(ids: Set[Long])
