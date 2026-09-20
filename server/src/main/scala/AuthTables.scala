package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.model.{
  Access, Grant, Group, Principal, Resource, User,
}
import slick.jdbc.JdbcProfile
import slick.jdbc.meta.MTable

/**
  * The tables this library stores its users, sessions and groups in, defined
  * against whichever JDBC profile the host application uses rather than a fixed
  * one. Create an instance with the application's own profile and share it
  * between the stores.
  *
  * No table declares a composite primary key or a foreign key. Where a
  * composite key would otherwise apply, the stores maintain uniqueness
  * themselves, under the row locks described on [[GroupStore]]. A table's
  * indexes and keys are created with the table itself, and so exactly once: see
  * [[createIfNotExists]].
  *
  * @param profile
  *   The Slick profile of the host application's database.
  *
  * @param prefix
  *   Prepended to every table name, for applications that need this library's
  *   tables to sit in a namespace of their own. It goes into the identifiers
  *   themselves, so it must be a constant the application chooses, never
  *   anything that arrived in a request.
  */
final class AuthTables
  (
    val profile: JdbcProfile,
    prefix: String = "",
  ):

  import profile.api.*

  /**
    * One registered user. The password column holds a salted hash in the format
    * produced by [[Passwords]], never a password.
    */
  final class Users(tag: Tag) extends Table[UserRow](tag, s"${ prefix }users"):

    def id           = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def username     = column[String]("username", O.Unique)
    def passwordHash = column[String]("password")

    override def * = (id, username, passwordHash).mapTo[UserRow]

  /**
    * One sign-in session, expiring at a time this library enforces itself. The
    * column holds the hash of the token, never the token; it keeps its name so
    * that a database created by an earlier version goes on working, with its
    * sessions simply no longer resolving.
    */
  final class Sessions
    (tag: Tag)
    extends Table[SessionRow](tag, s"${ prefix }sessions"):

    def tokenHash = column[String]("token", O.PrimaryKey)
    def userId    = column[Long]("user_id")
    def expires   = column[Long]("expires")

    def bySession = index(s"${ prefix }sessions_user", userId)

    def byExpiry = index(s"${ prefix }sessions_expiry", expires)

    override def * = (tokenHash, userId, expires).mapTo[SessionRow]

  /** One user group, nested inside another via its `parent` column. */
  final class Groups
    (tag: Tag)
    extends Table[GroupRow](tag, s"${ prefix }user_groups"):

    def id     = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def owner  = column[Long]("owner")
    def name   = column[String]("name")
    def parent = column[Option[Long]]("parent")

    def byOwner = index(s"${ prefix }groups_owner", owner)

    override def * = (id, owner, name, parent).mapTo[GroupRow]

  /** One user's membership of one group. */
  final class Members
    (tag: Tag)
    extends Table[MemberRow](tag, s"${ prefix }group_members"):

    def groupId = column[Long]("group_id")
    def userId  = column[Long]("user_id")

    def byGroup = index(
      s"${ prefix }members_group",
      (groupId, userId),
    )

    def byMember = index(s"${ prefix }members_user", userId)

    override def * = (groupId, userId).mapTo[MemberRow]

  /** One pending invitation of one user to one group. */
  final class Invitations
    (tag: Tag)
    extends Table[InvitationRow](tag, s"${ prefix }group_invitations"):

    def id      = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def groupId = column[Long]("group_id")
    def userId  = column[Long]("user_id")

    def byGroup = index(
      s"${ prefix }invitations_group",
      (groupId, userId),
    )

    def byInvitee = index(s"${ prefix }invitations_user", userId)

    override def * = (id, groupId, userId).mapTo[InvitationRow]

  /**
    * One principal's access to one resource. Resources are named by a kind and
    * identifier of the host application's choosing, and principals and levels
    * of access by their explicitly spelled names, so nothing here depends on a
    * position or a Scala identifier. At most one row exists per principal per
    * resource, maintained by [[GrantStore]].
    */
  final class Grants
    (tag: Tag)
    extends Table[GrantRow](tag, s"${ prefix }grants"):

    def id            = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def resourceKind  = column[String]("resource_kind")
    def resourceId    = column[Long]("resource_id")
    def principalKind = column[String]("principal_kind")
    def principalId   = column[Long]("principal_id")
    def access        = column[String]("access")

    def byResource = index(
      s"${ prefix }grants_resource",
      (resourceKind, resourceId),
    )

    def byPrincipal = index(
      s"${ prefix }grants_principal",
      (principalKind, principalId),
    )

    override def * = (
      id,
      resourceKind,
      resourceId,
      principalKind,
      principalId,
      access,
    ).mapTo[GrantRow]

  /** One unused recovery code of one user, stored only as a hash. */
  final class Codes
    (tag: Tag)
    extends Table[RecoveryCodeRow](tag, s"${ prefix }recovery_codes"):

    def id       = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def userId   = column[Long]("user_id")
    def codeHash = column[String]("code_hash")

    def byOwner = index(s"${ prefix }codes_user", userId)

    override def * = (id, userId, codeHash).mapTo[RecoveryCodeRow]

  /** The query for the table of registered users. */
  val users = TableQuery[Users]

  /** The query for the table of sign-in sessions. */
  val sessions = TableQuery[Sessions]

  /** The query for the table of user groups. */
  val groups = TableQuery[Groups]

  /** The query for the table of group memberships. */
  val members = TableQuery[Members]

  /** The query for the table of group invitations. */
  val invitations = TableQuery[Invitations]

  /** The query for the table of grants. */
  val grants = TableQuery[Grants]

  /** The query for the table of unused recovery codes. */
  val recoveryCodes = TableQuery[Codes]

  /**
    * Creates any of this library's tables that the database does not already
    * have, each with its indexes, and leaves every table it does have exactly
    * as it is. Safe to run on every startup, and alongside the host
    * application's own schema creation. There are no migrations: a table whose
    * shape has changed since an older database was created is not altered, so
    * such a database must be recreated.
    *
    * Which tables are missing is asked of the database's own metadata, rather
    * than left to `CREATE TABLE IF NOT EXISTS`, because that guards only the
    * table: Slick emits an index, a composite key or a foreign key as a
    * statement of its own, which a second startup would replay and fail on.
    */
  def createIfNotExists: DBIO[Unit] = MTable
    .getTables
    .flatMap(held => DBIO.seq(absent(held.map(_.name.name.toLowerCase).toSet)*))

  /** Creates each of this library's tables that is not among the given names. */
  private def absent(held: Set[String]): Seq[DBIO[Unit]] = tables
    .filterNot(table => held(table.baseTableRow.tableName.toLowerCase))
    .map(_.schema.create)

  /** Every table this library stores its data in. */
  private def tables: Seq[TableQuery[? <: Table[?]]] = Seq(
    users,
    sessions,
    groups,
    members,
    invitations,
    grants,
    recoveryCodes,
  )

/**
  * One registered user, flattened into a database row.
  *
  * @param id
  *   The unique identifier of this user, assigned by the database.
  *
  * @param username
  *   The unique name this user signs in with.
  *
  * @param passwordHash
  *   The salted hash of this user's password, in the format produced by
  *   [[Passwords]].
  */
final case class UserRow
  (
    id: Long,
    username: String,
    passwordHash: String,
  ):

  /** Restores the user stored in this row, without their password hash. */
  def toUser: User = User(id, username)

/**
  * One sign-in session, flattened into a database row.
  *
  * @param tokenHash
  *   The hash of the secret bearer token held in the browser's session cookie,
  *   as [[Digest]] produces it. The token itself is never stored, so a stolen
  *   copy of this table is not a set of usable sessions.
  *
  * @param userId
  *   The identifier of the signed-in user.
  *
  * @param expires
  *   When this session stops being accepted, in epoch milliseconds. Enforced
  *   server-side, so that a leaked token does not outlive it however long the
  *   browser chooses to keep the cookie.
  */
final case class SessionRow
  (
    tokenHash: String,
    userId: Long,
    expires: Long,
  )

/**
  * One user group, flattened into a database row.
  *
  * @param id
  *   The unique identifier of this group, assigned by the database.
  *
  * @param owner
  *   The identifier of the user who created, and therefore manages, this group.
  *
  * @param name
  *   The display name of this group.
  *
  * @param parent
  *   The identifier of the group this group is nested inside, or `None` for a
  *   top-level group. Always a group of the same owner.
  */
final case class GroupRow
  (
    id: Long,
    owner: Long,
    name: String,
    parent: Option[Long],
  ):

  /** Restores the group stored in this row. */
  def toGroup: Group = Group(id, name, parent)

/**
  * One user's membership of one group, flattened into a database row.
  *
  * @param groupId
  *   The identifier of the group.
  *
  * @param userId
  *   The identifier of the enrolled user.
  */
final case class MemberRow(groupId: Long, userId: Long)

/**
  * One pending invitation of one user to one group, flattened into a database
  * row. At most one exists per user per group, maintained by [[GroupStore]]. An
  * invitation is deleted once accepted or declined.
  *
  * @param id
  *   The unique identifier of this invitation, assigned by the database.
  *
  * @param groupId
  *   The identifier of the group the user is invited to.
  *
  * @param userId
  *   The identifier of the invited user.
  */
final case class InvitationRow(id: Long, groupId: Long, userId: Long)

/**
  * One unused recovery code of one user, flattened into a database row. A code
  * is deleted once used, and every code of a user when they generate a new set.
  *
  * @param id
  *   The unique identifier of this row, assigned by the database.
  *
  * @param userId
  *   The identifier of the user the code regains.
  *
  * @param codeHash
  *   The hash of the code, as [[RecoveryCode.hash]] produces it.
  */
final case class RecoveryCodeRow(id: Long, userId: Long, codeHash: String)

/**
  * One grant, flattened into a database row.
  *
  * @param id
  *   The unique identifier of this row, assigned by the database. It identifies
  *   the row only; a grant is identified by its resource and principal.
  *
  * @param resourceKind
  *   The kind of the resource, as named by the host application.
  *
  * @param resourceId
  *   The identifier of the resource among those of its kind.
  *
  * @param principalKind
  *   The stored name of the kind of principal (see [[Principal.kind]]).
  *
  * @param principalId
  *   The identifier of the user or group.
  *
  * @param access
  *   The stored name of the level of access (see [[Access.name]]).
  */
final case class GrantRow
  (
    id: Long,
    resourceKind: String,
    resourceId: Long,
    principalKind: String,
    principalId: Long,
    access: String,
  ):

  /**
    * Restores the grant stored in this row, or `None` if it names a kind of
    * principal or a level of access this version does not know. Such a row then
    * confers nothing, failing closed rather than guessing.
    */
  def toGrant: Option[Grant] =
    for
      principal <- Principal.of(principalKind, principalId)
      level     <- Access.fromName(access)
    yield Grant(
      Resource(resourceKind, resourceId),
      principal,
      level,
    )

object GrantRow:

  /** Flattens a grant into the row it is stored as, awaiting an identifier. */
  def of(grant: Grant): GrantRow = GrantRow(
    0,
    grant.resource.kind,
    grant.resource.id,
    grant.principal.kind,
    grant.principal.id,
    grant.access.name,
  )
