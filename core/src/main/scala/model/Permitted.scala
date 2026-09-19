package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * A value, annotated with the access its reader holds over it. Access is
  * resolved afresh for each reader and each request, and is never stored, so it
  * travels beside a value rather than inside it.
  *
  * @param item
  *   The value.
  *
  * @param access
  *   The access that the reader holds over the value.
  */
final case class Permitted[X](item: X, access: Access) derives Codec.AsObject
