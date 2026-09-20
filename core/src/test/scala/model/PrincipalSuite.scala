package com.alecdorrington.hecate
package model

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

class PrincipalSuite extends FunSuite:

  test("a principal round-trips through JSON as either case"):
    List(
      Principal.Person(1),
      Principal.Group(2),
    ).foreach(principal =>
      assertEquals(
        principal.asJson.as[Principal],
        Right(principal),
      ),
    )

  test("a principal is sent as its spelled kind, never its case name"):
    List[(Principal, String)](
      Principal.Person(7) -> "person",
      Principal.Group(7)  -> "group",
    ).foreach((principal, kind) =>
      assertEquals(
        principal.asJson.hcursor.downField("kind").as[String],
        Right(kind),
      ),
    )

  test("a kind this version does not know is refused rather than guessed at"):
    assertEquals(Principal.of("robot", 1), None)
    assert(
      decode[Principal]("""{"kind":"robot","id":1}""").isLeft,
      "an unknown kind decoded to something",
    )

  test("every kind is restored from what it is stored as"):
    List(
      Principal.Person(5),
      Principal.Group(5),
    ).foreach(principal =>
      assertEquals(
        Principal.of(principal.kind, principal.id),
        Some(principal),
      ),
    )

  test("a user and a group sharing an identifier are different principals"):
    val person: Principal = Principal.Person(7)
    val group: Principal  = Principal.Group(7)
    assertNotEquals(person, group)
    assertEquals(person.id, group.id)
