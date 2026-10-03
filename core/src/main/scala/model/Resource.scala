package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * A resource that access may be granted over, named in the host's own terms.
  *
  * @param kind
  *   The kind of the resource (e.g. `document`), chosen by the host.
  *
  * @param id
  *   The identifier of the resource among those of its kind.
  */
final case class Resource(kind: String, id: Long) derives Codec.AsObject
