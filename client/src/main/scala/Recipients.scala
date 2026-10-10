package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.model.{Access, ManagedGroup, Principal, User}
import com.raquo.laminar.api.L.*

/**
  * Whom the signed-in user may address: themselves, the system if they act for
  * it, the groups they may change, and every accepted member of those groups,
  * as the server's own rule allows.
  *
  * @param auth
  *   The sign-in state of the user.
  *
  * @param managed
  *   The groups the user manages.
  */
final class Recipients(auth: AuthState, managed: GroupsState):

  /**
    * The groups the user may address, in tree order, with their depths in the
    * tree of every group they manage.
    */
  val groups: Signal[List[(ManagedGroup, Int)]] = managed
    .forest
    .map(_.flatMap(_.outline()).filter(_._1.access.includes(Access.Edit)))

  /**
    * The people the user may address: themselves first, then the accepted
    * members of the groups they may change.
    */
  val people: Signal[List[User]] = auth
    .user
    .combineWith(groups)
    .mapN((me, groups) =>
      (me.toList ++ groups.flatMap(_._1.members.sortBy(_.username))).distinctBy(
        _.id,
      ),
    )

  /** Whether the user may address the system, acting for it. */
  val system: Signal[Boolean] = managed.system

  /** Everyone the user may address, as principals. */
  val principals: Signal[Set[Principal]] = groups
    .combineWith(people, system)
    .mapN((groups, people, system) =>
      groups.map((view, _) => Principal.Group(view.group.id)).toSet ++
        people.map(user => Principal.Person(user.id)) ++
        Option.when(system)(Principal.System),
    )
