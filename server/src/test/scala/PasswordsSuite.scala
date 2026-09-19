package com.alecdorrington.hecate
package server

import cats.syntax.all.*
import munit.CatsEffectSuite

class PasswordsSuite extends CatsEffectSuite:

  test("a hashed password verifies against itself"):
    Passwords
      .hash("correct horse battery staple")
      .flatMap(Passwords.verify("correct horse battery staple", _))
      .assertEquals(true)

  test("a wrong password does not verify"):
    Passwords
      .hash("correct horse battery staple")
      .flatMap(Passwords.verify("Tr0ub4dor&3", _))
      .assertEquals(false)

  test("hashing the same password twice yields different hashes"):
    for
      first  <- Passwords.hash("password123")
      second <- Passwords.hash("password123")
    yield assertNotEquals(first, second)

  test("a malformed stored hash never verifies"):
    List(
      "not a stored hash",
      "12:%%%:###",
      "120000::",
      "",
    ).traverse_(Passwords.verify("anything", _).assertEquals(false))

  test("no password verifies against the decoy"):
    Passwords.verify("anything", Passwords.decoy).assertEquals(false)

  /**
    * The decoy exists so that an unknown username costs a full derivation and
    * cannot be told apart by timing, which only holds while it stays in the
    * shape [[Passwords.verify]] takes all the way to the hash comparison. An
    * empty salt or hash would be rejected outright, silently reopening the
    * timing channel.
    */
  test("the decoy is well formed, so verifying it costs a real derivation"):
    Passwords.decoy.split(':') match
      case Array(rounds, salt, hash) =>
        assert(rounds.toIntOption.exists(_ > 0))
        assert(salt.nonEmpty)
        assert(hash.nonEmpty)
      case other => fail(s"The decoy is malformed: ${ other.mkString(":") }")
