package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.model.{OwnedGroup, Principal, User}
import com.raquo.laminar.api.L.*

/**
  * Whom the signed-in user may address: themselves, the groups they own, and
  * every accepted member of those groups, as the server's own rule allows.
  *
  * @param auth
  *   The sign-in state of the user.
  *
  * @param owned
  *   The groups the user owns.
  */
final class Recipients(auth: AuthState, owned: GroupsState):

  /** The groups the user may address, in tree order, with their depths. */
  val groups: Signal[List[(OwnedGroup, Int)]] = owned
    .forest
    .map(_.flatMap(_.outline()))

  /**
    * The people the user may address: themselves first, then their groups'
    * accepted members.
    */
  val people: Signal[List[User]] = auth
    .user
    .combineWith(owned.forest)
    .mapN((me, forest) =>
      (me.toList ++ forest.flatMap(_.members)).distinctBy(_.id),
    )

  /** Everyone the user may address, as principals. */
  val principals: Signal[Set[Principal]] = groups
    .combineWith(people)
    .mapN((groups, people) =>
      groups.map((view, _) => Principal.Group(view.group.id)).toSet ++
        people.map(user => Principal.Person(user.id)),
    )
