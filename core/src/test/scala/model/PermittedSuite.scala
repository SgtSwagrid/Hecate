package com.alecdorrington.hecate
package model

import io.circe.syntax.*
import munit.FunSuite
import sttp.tapir.{Schema, SchemaType}

class PermittedSuite extends FunSuite:

  private given Schema[Access] =
    Schema.derivedEnumeration[Access](encode = Some(_.name))

  private given Schema[Principal]        = Schema.derived
  private given Schema[Resource]         = Schema.derived
  private given Schema[Grant]            = Schema.derived
  private given Schema[Permitted[Grant]] = Schema.derived

  private val permitted = Permitted(
    Grant(
      Resource("document", 7),
      Principal.Group(3),
      Access.Own,
    ),
    Access.Edit,
  )

  test("a permitted value round-trips through JSON"):
    assertEquals(
      permitted.asJson.as[Permitted[Grant]],
      Right(permitted),
    )

  test("the reader's access is sent beside the item by name"):
    assertEquals(
      permitted.asJson.hcursor.downField("access").as[String],
      Right("edit"),
    )

  test("a permitted value derives a schema naming both of its fields"):
    val fields = summon[Schema[Permitted[Grant]]].schemaType match
      case product: SchemaType.SProduct[?] => product.fields.map(_.name.name)
      case _                               => Nil
    assertEquals(fields, List("item", "access"))
