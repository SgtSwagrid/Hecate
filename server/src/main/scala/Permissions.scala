package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{Access, Principal, Resource}

/**
  * Resolves what a user may do, by joining the grants over a resource with the
  * groups the user effectively belongs to. Only stored grants are considered:
  * any access a host application derives from its own data (such as authorship)
  * is layered on top by the host, which alone knows what its resources are.
  *
  * @param groups
  *   The store whose group memberships determine which grants reach a user.
  *
  * @param grants
  *   The store of the grants themselves.
  */
final class Permissions(groups: GroupStore, grants: GrantStore):

  /**
    * The highest stored access that reaches one user over one resource.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param resource
    *   The resource to resolve access over.
    *
    * @return
    *   A highest level of access reaching the user, or `None` if no grant does.
    */
  def access(user: Long, resource: Resource): IO[Option[Access]] = groups
    .groupIdsOf(user)
    .flatMap(grants.access(user, _, resource))

  /**
    * The identifiers of every resource of one kind over which one user holds at
    * least the given stored access.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param kind
    *   The kind of resource to list, as named by the host application.
    *
    * @param least
    *   The lowest level of access that counts.
    *
    * @return
    *   A set of the identifiers of every such resource, possibly empty.
    */
  def visible(user: Long, kind: String, least: Access): IO[Set[Long]] = groups
    .groupIdsOf(user)
    .flatMap(grants.visible(user, _, kind, least))

  /**
    * The highest stored access one user holds over every resource of one kind
    * that any grant reaching them names, resolved together rather than one
    * resource at a time.
    *
    * @param user
    *   The identifier of the user.
    *
    * @param kind
    *   The kind of resource to resolve, as named by the host application.
    *
    * @return
    *   A map from the identifier of each such resource to the highest level of
    *   access the user holds over it.
    */
  def levels(user: Long, kind: String): IO[Map[Long, Access]] = groups
    .groupIdsOf(user)
    .flatMap(grants.levels(user, _, kind))

  /**
    * Everyone that stored access of at least the given level over one resource
    * reaches: whoever it is granted to personally, and every member of any
    * group it is granted to, or of a group nested inside one. The reverse of
    * [[access]], for telling whoever holds a resource that it has changed.
    *
    * @param resource
    *   The resource whose holders to find.
    *
    * @param least
    *   The lowest level of access that counts.
    *
    * @return
    *   A set of the identifiers of every such user, possibly empty.
    */
  def holders
    (
      resource: Resource,
      least: Access = Access.View,
    )
    : IO[Set[Long]] = grants
    .grantsOver(resource)
    .map(_.filter(_.access.includes(least)).map(_.principal))
    .flatMap(principals =>
      groups
        .membersWithin(principals.collect { case Principal.Group(id) => id })
        .map(_.toSet ++ principals.collect { case Principal.Person(id) => id }),
    )
