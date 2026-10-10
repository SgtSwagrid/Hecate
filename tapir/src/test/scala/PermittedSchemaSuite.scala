package com.alecdorrington.hecate
package tapir

import com.alecdorrington.hecate.model.{Gated, Grant, Permitted, Principal}
import com.alecdorrington.hecate.tapir.Schemas.given
import munit.FunSuite
import sttp.tapir.{Schema, SchemaType}

/**
  * Tests that the schemas of the generic types of the model serve a host's own
  * endpoints, describing the shapes their codecs send.
  */
class PermittedSchemaSuite extends FunSuite:

  /** The names of the fields a schema describes. */
  private def fields(schema: Schema[?]): List[String] = schema.schemaType match
    case product: SchemaType.SProduct[?] => product.fields.map(_.name.name)
    case _                               => Nil

  test("a permitted value, a principal and a gated part name their fields"):
    assertEquals(
      fields(summon[Schema[Permitted[Grant]]]),
      List("value", "access"),
    )
    assertEquals(
      fields(summon[Schema[Principal]]),
      List("kind", "id"),
    )
    assertEquals(
      fields(summon[Schema[Gated[String]]]),
      List("state", "value"),
    )
