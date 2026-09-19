package com.alecdorrington.hecate
package model

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

class GatedSuite extends FunSuite:

  test("every state round-trips through JSON"):
    List[Gated[Int]](
      Gated.Shown(3),
      Gated.Absent,
      Gated.Withheld,
    ).foreach(part =>
      assertEquals(
        part.asJson.as[Gated[Int]],
        Right(part),
      ),
    )

  test("each state is sent under its spelled name"):
    val absent: Gated[Int]   = Gated.Absent
    val withheld: Gated[Int] = Gated.Withheld
    assertEquals(
      absent.asJson,
      Json.obj("state" -> Json.fromString("absent")),
    )
    assertEquals(
      withheld.asJson,
      Json.obj("state" -> Json.fromString("withheld")),
    )

  test("a part shown as nothing stays shown rather than becoming absent"):
    val nothing: Gated[Option[Int]] = Gated.Shown(None)
    assertEquals(
      nothing.asJson,
      Json.obj(
        "state" -> Json.fromString("shown"),
        "value" -> Json.Null,
      ),
    )
    assertEquals(
      nothing.asJson.as[Gated[Option[Int]]],
      Right(nothing),
    )

  test("an unknown state is refused rather than guessed at"):
    val unknown = Json.obj("state" -> Json.fromString("hidden"))
    assert(unknown.as[Gated[Int]].isLeft)

  test("blank text is absent, and other text is shown"):
    assertEquals(Gated.text("  "), Gated.Absent)
    assertEquals(
      Gated.text("Sort by author."),
      Gated.Shown("Sort by author."),
    )
