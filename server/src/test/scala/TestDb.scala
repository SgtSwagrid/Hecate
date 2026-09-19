package com.alecdorrington.hecate
package server

import cats.effect.{IO, Resource}
import slick.dbio.DBIO
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api.*

/**
  * A throwaway in-memory database for tests, standing in for the host
  * application's own. H2 is used here only because the tests need some
  * database; the library itself is bound to no particular profile.
  *
  * @param underlying
  *   The underlying Slick database.
  */
final class TestDb(val underlying: Database) extends Transactor:

  override def run[X](action: DBIO[X]): IO[X] =
    IO.fromFuture(IO(underlying.run(action)))

object TestDb:

  /** The tables under test, bound to H2. */
  val tables: AuthTables = AuthTables(H2Profile)

  /**
    * Opens a fresh, empty database under the given name, with the auth tables
    * already created.
    */
  def open(name: String): Resource[IO, TestDb] = Resource
    .make(IO(TestDb(Database.forURL(
      s"jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1",
      driver = "org.h2.Driver",
    ))))(db => IO(db.underlying.close()))
    .evalTap(_.run(tables.createIfNotExists))

  /** Opens a fresh database and builds a user store over it. */
  def users(name: String): Resource[IO, UserStore] =
    open(name).map(UserStore(tables, _))

  /** Opens a fresh database and builds the group and user stores over it. */
  def groups(name: String): Resource[IO, (GroupStore, UserStore)] = open(name)
    .map(db => (GroupStore(tables, db), UserStore(tables, db)))
