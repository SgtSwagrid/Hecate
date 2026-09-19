package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * One resource that access may be granted over, named in terms chosen by the
  * host application, so that this library never needs to know what the
  * application stores.
  *
  * @param kind
  *   The kind of the resource (e.g. `document`), chosen by the host
  *   application.
  *
  * @param id
  *   The identifier of the resource among those of its kind.
  */
final case class Resource(kind: String, id: Long) derives Codec.AsObject
