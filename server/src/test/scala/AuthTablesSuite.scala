package com.alecdorrington.hecate
package server

import munit.CatsEffectSuite
import slick.jdbc.{H2Profile, JdbcProfile, SQLiteProfile}

/** Tests of the schema this library creates for itself. */
final class AuthTablesSuite extends CatsEffectSuite:

  /** The tables over one profile, and the statements that profile writes. */
  private final class Written(profile: JdbcProfile):

    private val tables = AuthTables(profile)

    import tables.profile.api.*

    /** The SQL of reading one user's row under the stores' row lock. */
    val lockedUser: String = tables
      .locked(tables.users.filter(_.id === 1L))
      .result
      .statements
      .mkString

  test("the tables can be created again on a database that has them"):
    TestDb
      .open("schema-twice")
      .use(db => db.run(TestDb.tables.createIfNotExists))

  /**
    * A profile without `SELECT … FOR UPDATE` still writes the clause when a
    * query asks for it, and SQLite then refuses the whole statement, so every
    * row lock the stores take must leave it out there. Read off the statement
    * itself, since every other test here runs on H2 and would never notice.
    */
  test("rows are locked on H2, and read unlocked on SQLite, which has none"):
    assert(Written(H2Profile).lockedUser.contains("for update"))
    assert(!Written(SQLiteProfile).lockedUser.contains("for update"))
