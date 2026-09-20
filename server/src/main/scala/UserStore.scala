package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.hecate.model.User
import java.sql.SQLException
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

  /**
    * Stores a new user under the given password hash, or returns `None` when
    * the username is already taken.
    *
    * Surrounding space is trimmed by the service before it arrives here, but
    * nothing else is: the name is stored as given and matched exactly, letter
    * case and all. A host wanting `Alice` and `alice` to be one account folds
    * the case itself, before registering and before signing in.
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
  def byIds(ids: Seq[Long]): IO[Map[Long, User]] = db.run(
    ifAny(ids.distinct)(Map.empty)(found =>
      tables
        .users
        .filter(_.id inSet found)
        .result
        .map(_.map(row => row.id -> row.toUser).toMap),
    ),
  )

  /** Finds one stored user by identifier, including their password hash. */
  def findById(user: Long): IO[Option[UserRow]] =
    db.run(tables.users.filter(_.id === user).result.headOption)

  /**
    * Opens a sign-in session for the given user under the given token, expiring
    * at the given time. Only the hash of the token is stored, so that a stolen
    * copy of the table is not a set of usable sessions.
    */
  def openSession(token: String, user: Long, expires: Long): IO[Unit] = db
    .run(tables.sessions += SessionRow(Digest.of(token), user, expires))
    .void

  /**
    * Deletes every session that has expired, whoever it belonged to. Nothing
    * here calls this: an expired session is refused by [[sessionUser]] whether
    * or not its row is still there, so this is housekeeping, and a host
    * application schedules it as it sees fit rather than paying for it inside
    * somebody else's request.
    */
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
            session.tokenHash === Digest.of(token) &&
            session.expires > now.toMillis,
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
    .run(tables.sessions.filter(_.tokenHash === Digest.of(token)).delete)
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
    : IO[Option[User]] = db.run(
    byUsername(username)
      .result
      .headOption
      .flatMap(regain(_, codeHash, passwordHash, token, expires))
      .transactionally,
  )

  /**
    * Regains the given account, if there is one and the code is one of its
    * unused codes, and answers with nobody otherwise.
    */
  private def regain
    (
      found: Option[UserRow],
      codeHash: String,
      passwordHash: String,
      token: String,
      expires: Long,
    )
    : DBIO[Option[User]] = found.fold(DBIO.successful(None))(row =>
    claim(row.id, codeHash).flatMap(claimed =>
      if claimed then
        reset(row.id, passwordHash, token, expires).map(_ => Some(row.toUser))
      else DBIO.successful(None),
    ),
  )

  /**
    * Uses up one of a user's recovery codes, answering whether it was one of
    * theirs. Deleting it both checks it and claims it in one step, so that two
    * recoveries racing with the same code cannot both succeed.
    */
  private def claim(user: Long, codeHash: String): DBIO[Boolean] =
    codeOf(user, codeHash).delete.map(_ == 1)

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
    * Replaces one user's password hash, leaving their sessions alone: the
    * password itself has not changed, only the cost it is stored under, so
    * nobody need be signed out.
    */
  private[server] def rehash(user: Long, passwordHash: String): IO[Unit] = db
    .run(
      tables
        .users
        .filter(_.id === user)
        .map(_.passwordHash)
        .update(passwordHash),
    )
    .void

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
    tables.sessions += SessionRow(Digest.of(token), user, expires),
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
