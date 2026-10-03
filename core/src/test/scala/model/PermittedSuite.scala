package com.alecdorrington.hecate
package model

import io.circe.syntax.*
import munit.FunSuite

class PermittedSuite extends FunSuite:

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

  test("the reader's access is sent beside the value by code"):
    assertEquals(
      permitted.asJson.hcursor.downField("access").as[String],
      Right("edit"),
    )
