package com.alecdorrington.hecate
package tapir

import com.alecdorrington.hecate.model.Principal
import sttp.tapir.*

/** How a principal appears in a path, as in `person/7` or `group/7`. */
object Principals:

  /**
    * The path segments naming a principal.
    *
    * @return
    *   An input reading a principal from its kind and identifier.
    */
  def path: EndpointInput[Principal] =
    (sttp.tapir.path[String]("kind") / sttp.tapir.path[Long]("id")).map(
      Mapping.fromDecode[(String, Long), Principal]((kind, id) =>
        Principal
          .of(kind, id)
          .fold(DecodeResult.Mismatch("person or group", kind))(
            DecodeResult.Value(_),
          ),
      )(principal => (principal.kind, principal.id)),
    )
