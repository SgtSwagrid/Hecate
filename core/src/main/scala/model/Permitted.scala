package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * A value with the access its reader holds over it. Access is resolved per
  * reader and request, and never stored.
  *
  * @tparam X
  *   The type of the value.
  *
  * @param value
  *   The value.
  *
  * @param access
  *   The access the reader holds over the value.
  */
final case class Permitted[+X](value: X, access: Access) derives Codec.AsObject
