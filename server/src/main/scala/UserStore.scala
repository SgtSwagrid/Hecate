package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.hecate.model.User
import java.sql.SQLException
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
  * The store of registered users, their sign-in sessions, and their recovery
  * codes.
  *
  * @param tables
  *   The tables the users, sessions and codes are stored in.
  *
  * @param db
  *   The database to run the queries against.
  */
final class UserStore(tables: AuthTables, db: Transactor):

  import tables.profile.api.*

  private given ExecutionContext = ExecutionContext.parasitic

  /**
    * Stores a new user under the given password hash, or returns `None` when
    * the username is already taken.
    *
    * The username column is unique in the database itself, so the insert alone
    * decides: checking first would not help, as two registrations racing for
    * the same name could both find it free. Whichever inserts second is refused
    * by the database, and that refusal is what reports the name as taken.
    */
  def register(username: String, passwordHash: String): IO[Option[User]] = db
    .run(
      (tables.users.returning(tables.users.map(_.id)) +=
        UserRow(0, username, passwordHash))
        .asTry
        .flatMap:
          case Success(id) => DBIO.successful(Some(User(id, username)))
          case Failure(error) if UserStore.violatesConstraint(error) =>
            DBIO.successful(None)
          case Failure(error) => DBIO.failed(error),
    )

  /** Finds one stored user by username, including their password hash. */
  def findByUsername(username: String): IO[Option[UserRow]] =
    db.run(byUsername(username).result.headOption)

  /**
    * The users with the given identifiers, keyed by identifier; an identifier
    * with no user is simply absent. One query, and no empty query at all.
    *
    * A raw lookup, with no notion of who may see whom. A host application must
    * decide which of these users the caller may see before calling this, and
    * must never expose it directly, or it becomes a directory of every account.
    */
  def named(ids: Seq[Long]): IO[Map[Long, User]] =
    if ids.isEmpty then IO.pure(Map.empty)
    else
      db.run(tables.users.filter(_.id inSet ids.distinct).result)
        .map(_.map(row => row.id -> row.toUser).toMap)

  /** Finds one stored user by identifier, including their password hash. */
  def findById(user: Long): IO[Option[UserRow]] =
    db.run(tables.users.filter(_.id === user).result.headOption)

  /**
    * Opens a sign-in session for the given user under the given token, expiring
    * at the given time.
    */
  def openSession(token: String, user: Long, expires: Long): IO[Unit] = db
    .run(tables.sessions += SessionRow(token, user, expires))
    .void

  /** Deletes every session that has expired, whoever it belonged to. */
  def purgeExpired: IO[Unit] = IO
    .realTime
    .flatMap(now =>
      db.run(tables.sessions.filter(_.expires <= now.toMillis).delete).void,
    )

  /** The user signed in under the given session token, if it has not expired. */
  def sessionUser(token: String): IO[Option[User]] = IO
    .realTime
    .flatMap(now =>
      db.run(
        tables
          .sessions
          .filter(session =>
            session.token === token && session.expires > now.toMillis,
          )
          .join(tables.users)
          .on(_.userId === _.id)
          .map(_._2)
          .result
          .headOption,
      ),
    )
    .map(_.map(_.toUser))

  /** Closes the session with the given token, signing its user out. */
  def closeSession(token: String): IO[Unit] = db
    .run(tables.sessions.filter(_.token === token).delete)
    .void

  /**
    * Replaces the given user's password, signing out every session they had and
    * opening the one given in their place, so that whoever knew the old
    * password is signed out along with them.
    */
  def resetPassword
    (
      user: Long,
      passwordHash: String,
      token: String,
      expires: Long,
    )
    : IO[Unit] =
    db.run(reset(user, passwordHash, token, expires).transactionally)

  /**
    * Regains the account with the given username using one of its recovery
    * codes: uses the code up, then resets the password as [[resetPassword]]
    * does. Returns the user, or `None`, changing nothing, when there is no such
    * account or the code is not one of its unused codes. The two cases are not
    * told apart, so that this cannot be used to find usernames.
    */
  def recover
    (
      username: String,
      codeHash: String,
      passwordHash: String,
      token: String,
      expires: Long,
    )
    : IO[Option[User]] = db.run((for
    found <- byUsername(username).result.headOption
    // Deleting the code both checks it and uses it up in one step, so that
    // two recoveries racing with the same code cannot both succeed.
    used <- found.fold[DBIO[Int]](DBIO.successful(0))(row =>
      codeOf(row.id, codeHash).delete,
    )
    user <- found match
      case Some(row) if used == 1 =>
        reset(row.id, passwordHash, token, expires).map(_ => Some(row.toUser))
      case _ => DBIO.successful(None)
  yield user).transactionally)

  /**
    * Replaces every recovery code of the given user with the given set, so that
    * any code written down before stops working.
    */
  def replaceRecoveryCodes(user: Long, codeHashes: Seq[String]): IO[Unit] = db
    .run(
      DBIO
        .seq(
          codesOf(user).delete,
          tables.recoveryCodes ++= codeHashes.map(RecoveryCodeRow(0, user, _)),
        )
        .transactionally,
    )

  /** How many unused recovery codes the given user has left. */
  def recoveryCodesLeft(user: Long): IO[Int] =
    db.run(codesOf(user).length.result)

  /**
    * Removes one user's sessions, recovery codes and account. Composes into an
    * account's deletion, which removes everything else of theirs first.
    */
  private[server] def remove(user: Long): DBIO[Unit] = DBIO.seq(
    tables.sessions.filter(_.userId === user).delete,
    codesOf(user).delete,
    tables.users.filter(_.id === user).delete,
  )

  /** Sets a password, closing every session and opening the given one. */
  private def reset
    (
      user: Long,
      passwordHash: String,
      token: String,
      expires: Long,
    )
    : DBIO[Unit] = DBIO.seq(
    tables.users.filter(_.id === user).map(_.passwordHash).update(passwordHash),
    tables.sessions.filter(_.userId === user).delete,
    tables.sessions += SessionRow(token, user, expires),
  )

  /** The query for the stored user with the given username. */
  private def byUsername(username: String) = tables
    .users
    .filter(_.username === username)

  /** The query for every unused recovery code of the given user. */
  private def codesOf(user: Long) = tables
    .recoveryCodes
    .filter(_.userId === user)

  /** The query for one unused recovery code of the given user, by its hash. */
  private def codeOf(user: Long, codeHash: String) =
    codesOf(user).filter(_.codeHash === codeHash)

object UserStore:

  /**
    * Whether a failure is the database refusing a write that would break an
    * integrity constraint, such as a second user with the same username.
    * Recognised by its standard SQLSTATE class, `23`, rather than by any one
    * driver's exception type, as drivers differ: H2 raises a dedicated
    * subclass, whereas PostgreSQL's driver reports it only through the state.
    */
  private def violatesConstraint(error: Throwable): Boolean = error match
    case sql: SQLException => Option(sql.getSQLState).exists(_.startsWith("23"))
    case _                 => false
