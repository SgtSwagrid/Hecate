package com.alecdorrington.hecate
package server

import cats.effect.{IO, Resource}
import java.util.UUID
import slick.dbio.DBIO
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api.*

/** A throwaway in-memory H2 database standing in for the host's. */
final class TestDb(val underlying: Database) extends Transactor:

  override def run[X](action: DBIO[X]): IO[X] =
    IO.fromFuture(IO(underlying.run(action)))

object TestDb:

  val tables: AuthTables = AuthTables(H2Profile)

  /** Opens a fresh database with the tables created. */
  def open(name: String): Resource[IO, TestDb] = Resource
    .make(IO(TestDb(Database.forURL(
      s"jdbc:h2:mem:$name-${ UUID.randomUUID };DB_CLOSE_DELAY=-1",
      driver = "org.h2.Driver",
    ))))(db => IO(db.underlying.close()))
    .evalTap(_.run(tables.createIfNotExists))

  def users(name: String): Resource[IO, UserStore] =
    open(name).map(UserStore(tables, _))

  def groups(name: String): Resource[IO, (GroupStore, UserStore)] = open(name)
    .map(db => (GroupStore(tables, db), UserStore(tables, db)))
