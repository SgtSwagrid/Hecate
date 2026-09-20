package com.alecdorrington.hecate
package server

import munit.CatsEffectSuite

/** Tests of the schema this library creates for itself. */
final class AuthTablesSuite extends CatsEffectSuite:

  test("the tables can be created again on a database that has them"):
    TestDb
      .open("schema-twice")
      .use(db => db.run(TestDb.tables.createIfNotExists))
