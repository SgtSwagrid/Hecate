package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * A resource that access may be granted over, named in the host's own terms,
  * or a group, which the library names itself.
  *
  * @param kind
  *   The kind of the resource (e.g. `document`), chosen by the host, or
  *   [[Resource.groupKind]] for a group.
  *
  * @param id
  *   The identifier of the resource among those of its kind.
  */
final case class Resource(kind: String, id: Long) derives Codec.AsObject

object Resource:

  /**
    * The kind of a group as a resource, over which access decides who manages
    * it. Reserved: a host must not name a kind of its own this.
    */
  val groupKind: String = "group"

  /**
    * Names a group as a resource.
    *
    * @param id
    *   The identifier of the group.
    *
    * @return
    *   The group as a resource.
    */
  def group(id: Long): Resource = Resource(groupKind, id)
