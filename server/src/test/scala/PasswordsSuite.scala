package com.alecdorrington.hecate
package server

import cats.syntax.all.*
import munit.CatsEffectSuite

class PasswordsSuite extends CatsEffectSuite:

  private val iterations = 1000

  test("a hashed password verifies against itself"):
    Passwords
      .hash(
        "correct horse battery staple",
        iterations,
      )
      .flatMap(Passwords.verify("correct horse battery staple", _))
      .assertEquals(true)

  test("a wrong password does not verify"):
    Passwords
      .hash(
        "correct horse battery staple",
        iterations,
      )
      .flatMap(Passwords.verify("Tr0ub4dor&3", _))
      .assertEquals(false)

  test("hashing the same password twice yields different hashes"):
    for
      first  <- Passwords.hash("password123", iterations)
      second <- Passwords.hash("password123", iterations)
    yield assertNotEquals(first, second)

  test("a malformed stored hash never verifies"):
    List(
      "not a stored hash",
      "12:%%%:###",
      "120000::",
      "",
      // A count no thread should obey, and one no derivation can use.
      "999999999999:c2FsdHk=:aGFzaHk=",
      "0:c2FsdHk=:aGFzaHk=",
      "-1:c2FsdHk=:aGFzaHk=",
    ).traverse_(Passwords.verify("anything", _).assertEquals(false))

  test("no password verifies against the decoy"):
    Passwords
      .verify(
        "anything",
        Passwords.decoy(iterations),
      )
      .assertEquals(false)

  test("the decoy is well formed, so verifying it costs a real derivation"):
    Passwords.decoy(iterations).split(':') match
      case Array(count, salt, hash) =>
        assertEquals(count.toIntOption, Some(iterations))
        assert(salt.nonEmpty)
        assert(hash.nonEmpty)
      case other => fail(s"The decoy is malformed: ${ other.mkString(":") }")

  test("a hash derived under fewer iterations is the one marked outdated"):
    Passwords
      .hash(
        "correct horse battery staple",
        iterations,
      )
      .map: hash =>
        assert(Passwords.outdated(hash, iterations + 1))
        assert(!Passwords.outdated(hash, iterations))
        assert(!Passwords.outdated(hash, 1))
        assert(!Passwords.outdated("nonsense", iterations + 1))
