package com.alecdorrington.hecate
package server

import munit.CatsEffectSuite

class RecoveryCodeSuite extends CatsEffectSuite:

  test("a fresh set holds distinct codes, each two groups of five"):
    RecoveryCode
      .generate
      .map: codes =>
        assertEquals(codes.size, RecoveryCode.setSize)
        assertEquals(codes.distinct.size, codes.size)
        assert(codes.forall(_.matches("[a-z2-9]{5}-[a-z2-9]{5}")))

  test("a code is recognised however it was copied out"):
    assertEquals(
      RecoveryCode.hash("abcde-fghjk"),
      RecoveryCode.hash(" ABCDE fghjk "),
    )

  test("different codes hash differently"):
    assertNotEquals(
      RecoveryCode.hash("abcde-fghjk"),
      RecoveryCode.hash("abcde-fghjm"),
    )
