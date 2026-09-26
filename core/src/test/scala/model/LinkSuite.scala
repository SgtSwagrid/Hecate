package com.alecdorrington.hecate
package model

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

class LinkSuite extends FunSuite:

  test("a code is five letters or digits, read without regard to case"):
    assertEquals(
      InviteCode.parse("k3x9q"),
      Some("k3x9q"),
    )
    assertEquals(
      InviteCode.parse("K3X9Q"),
      Some("k3x9q"),
    )
    assertEquals(
      InviteCode.parse("00000"),
      Some("00000"),
    )

  test("anything else is not a code"):
    List(
      "k3x9",
      "k3x9qz",
      "k3-9q",
      "k3x9é",
      "",
      "tasks/",
      "tasks",
      "Books",
    ).foreach(text => assertEquals(InviteCode.parse(text), None, text))

  test("the alphabet is the 26 letters and 10 digits"):
    assertEquals(InviteCode.alphabet.length, 36)
    assertEquals(
      InviteCode.alphabet.distinct,
      InviteCode.alphabet,
    )

  test("a target is sent as its kind and what it names"):
    assertEquals(
      (LinkTarget.Joining(7): LinkTarget).asJson,
      Json.obj(
        "kind"  -> "group".asJson,
        "group" -> 7.asJson,
      ),
    )
    assertEquals(
      (LinkTarget.Sharing(Resource("book", 3), Access.Edit): LinkTarget).asJson,
      Json.obj(
        "kind"     -> "resource".asJson,
        "resource" -> Resource("book", 3).asJson,
        "access"   -> "edit".asJson,
      ),
    )

  test("every target reads back as it was sent"):
    List[LinkTarget](
      LinkTarget.Joining(7),
      LinkTarget.Sharing(Resource("book", 3), Access.View),
    ).foreach(target =>
      assertEquals(
        decode[LinkTarget](target.asJson.noSpaces),
        Right(target),
      ),
    )

  test("a target of an unknown kind is refused"):
    assert(decode[LinkTarget]("""{"kind":"planet","id":1}""").isLeft)
