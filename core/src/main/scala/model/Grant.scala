package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * One principal's access to one resource, as stored. A user's access to a
  * resource is the highest level among the grants that reach them.
  *
  * @param resource
  *   The resource that access is granted over.
  *
  * @param principal
  *   The user or group that access is granted to.
  *
  * @param access
  *   The level of access granted.
  */
final case class Grant
  (
    resource: Resource,
    principal: Principal,
    access: Access,
  )
  derives Codec.AsObject
