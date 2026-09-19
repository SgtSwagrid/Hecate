package com.alecdorrington.hecate
package model

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

class AccessSuite extends FunSuite:

  test("each level includes itself and every level below it"):
    assert(Access.Own.includes(Access.View))
    assert(Access.Edit.includes(Access.Edit))
    assert(!Access.View.includes(Access.Edit))

  test("levels are ordered from least to most permissive"):
    assertEquals(
      List(Access.Own, Access.View, Access.Edit).sorted,
      List(Access.View, Access.Edit, Access.Own),
    )

  test("levels are stored under stable lower-case names"):
    assertEquals(
      Access.values.map(_.name).toList,
      List("view", "edit", "own"),
    )

  test("every level is found again by its name"):
    Access
      .values
      .foreach(level =>
        assertEquals(
          Access.fromName(level.name),
          Some(level),
        ),
      )

  test("a level is sent as its name rather than as an object"):
    assertEquals(
      Access.Edit.asJson,
      Json.fromString("edit"),
    )

  test("an unknown name is refused rather than defaulted"):
    assert(Json.fromString("admin").as[Access].isLeft)
