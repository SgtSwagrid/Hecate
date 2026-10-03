package com.alecdorrington.hecate
package server

import munit.CatsEffectSuite
import slick.jdbc.{H2Profile, JdbcProfile, SQLiteProfile}

final class AuthTablesSuite extends CatsEffectSuite:

  private final class Written(profile: JdbcProfile):

    private val tables = AuthTables(profile)

    import tables.profile.api.*

    val lockedUser: String = tables
      .locked(tables.users.filter(_.id === 1L))
      .result
      .statements
      .mkString

  test("the tables can be created again on a database that has them"):
    TestDb
      .open("schema-twice")
      .use(db => db.run(TestDb.tables.createIfNotExists))

  /** Checked in the SQL, as every other suite runs on H2 and cannot notice. */
  test("rows are locked on H2, and read unlocked on SQLite, which has none"):
    assert(Written(H2Profile).lockedUser.contains("for update"))
    assert(!Written(SQLiteProfile).lockedUser.contains("for update"))
