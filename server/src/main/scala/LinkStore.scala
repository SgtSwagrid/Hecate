package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.model.{
  Access, InviteCode, InviteLink, LinkTarget, Resource, User,
}
import java.security.SecureRandom
import slick.dbio.DBIO

/**
  * The store of invite links: each a code (see [[InviteCode]]) leading to one
  * group or one resource. Every action here composes into the caller's
  * transaction and opens none of its own, as who may make a link, and what must
  * be locked meanwhile, is decided by whoever calls it: [[GroupStore]] for a
  * group, and the host application for a resource.
  *
  * At most one link leads to each group and to each resource. As for grants, no
  * table can declare that, so it holds only while the caller locks the row of
  * what the link leads to before changing its link, as [[GroupStore]] does.
  *
  * @param tables
  *   The tables the links are stored in.
  */
final class LinkStore(tables: AuthTables):

  import tables.profile.api.*

  /** The link leading to the given target, if there is one, and who made it. */
  def to(target: LinkTarget): DBIO[Option[LinkRow]] = leadingTo(target)
    .result
    .headOption

  /**
    * The codes of the links to each of the given groups that has one. Never
    * asks the database about no groups.
    */
  def toGroups(groups: Seq[Long]): DBIO[Map[Long, String]] = ifAny(groups)(
    Map.empty[Long, String],
  ): shown =>
    tables
      .links
      .filter(_.groupId inSet shown)
      .map(link => (link.groupId, link.code))
      .result
      .map(_.collect { case (Some(group), code) => group -> code }.toMap)

  /** The link leading to the given resource, as its owners see it, if any. */
  def over(resource: Resource): DBIO[Option[InviteLink]] = to(
    LinkTarget.Sharing(resource, Access.View),
  ).map(_.flatMap(row =>
    row
      .target
      .collect { case LinkTarget.Sharing(_, access) =>
        InviteLink(row.code, access)
      },
  ))

  /**
    * The link with the given code, and the user who made it, if there is one.
    * The code is read without regard to case, and text that could not be a code
    * finds nothing without asking the database.
    */
  def find(code: String): DBIO[Option[(LinkRow, User)]] = InviteCode
    .parse(code)
    .fold(DBIO.successful(None))(known =>
      tables
        .links
        .filter(_.code === known)
        .join(tables.users)
        .on(_.creator === _.id)
        .result
        .headOption
        .map(_.map((row, creator) => (row, creator.toUser))),
    )

  /**
    * Gives the target a link unless it has one, yielding the link's code either
    * way. A resource's link is set to the target's level of access, whatever
    * level it granted before.
    *
    * @param creator
    *   The identifier of the user making the link, should one be made.
    *
    * @param target
    *   Where the link leads, and for a resource what it grants.
    */
  def ensure(creator: Long, target: LinkTarget): DBIO[String] = to(target)
    .flatMap:
      case Some(row) => regrant(target).map(_ => row.code)
      case None      => fresh(creator, target)

  /**
    * Replaces the target's link with one of a new code, yielding that code, so
    * that the old one stops working. Makes one if there was none.
    */
  def renew(creator: Long, target: LinkTarget): DBIO[String] = remove(target)
    .flatMap(_ => fresh(creator, target))

  /** Turns off the link leading to the target, if there is one. */
  def remove(target: LinkTarget): DBIO[Unit] = leadingTo(target).delete.unit

  /** Turns off every link leading to any of the given groups. */
  def removeFromGroups(groups: Seq[Long]): DBIO[Unit] = ifAny(groups)(()):
    doomed => tables.links.filter(_.groupId inSet doomed).delete.unit

  /** Turns off every link made by the given user. */
  def removeMadeBy(user: Long): DBIO[Unit] = tables
    .links
    .filter(_.creator === user)
    .delete
    .unit

  /**
    * The given action as one transaction, joining the caller's if there is one,
    * for a service that holds no profile of its own to open one with.
    */
  private[server] def atomically[X](action: DBIO[X]): DBIO[X] =
    action.transactionally

  /** Sets the level of access the target's link grants, if it grants any. */
  private def regrant(target: LinkTarget): DBIO[Unit] = target match
    case LinkTarget.Joining(_)         => DBIO.successful(())
    case LinkTarget.Sharing(_, access) =>
      leadingTo(target).map(_.access).update(Some(access.name)).unit

  /**
    * Stores a link to the target with a code no other link has, trying fresh
    * codes until one is free. With tens of millions of codes to choose from, a
    * second try is rare and a tenth all but impossible, so that is where it
    * gives up rather than spinning.
    */
  private def fresh
    (
      creator: Long,
      target: LinkTarget,
      tries: Int = LinkStore.tries,
    )
    : DBIO[String] = DBIO
    .successful(())
    .flatMap(_ => claim(LinkStore.randomCode(), creator, target))
    .flatMap:
      case Some(code)        => DBIO.successful(code)
      case None if tries > 1 => fresh(creator, target, tries - 1)
      case None              =>
        DBIO.failed(IllegalStateException("No free invite code was found."))

  /** Stores a link with the given code, unless the code is taken. */
  private def claim
    (
      code: String,
      creator: Long,
      target: LinkTarget,
    )
    : DBIO[Option[String]] = tables
    .links
    .filter(_.code === code)
    .exists
    .result
    .flatMap:
      case true  => DBIO.successful(None)
      case false =>
        (tables.links += LinkRow.of(code, creator, target)).map(_ => Some(code))

  /** The query for every link leading to the target, whatever it grants. */
  private def leadingTo(target: LinkTarget) = target match
    case LinkTarget.Joining(group) => tables.links.filter(_.groupId === group)
    case LinkTarget.Sharing(resource, _) => tables
        .links
        .filter(link =>
          link.resourceKind === resource.kind && link.resourceId === resource.id,
        )

object LinkStore:

  /** How many codes are tried before giving up on finding a free one. */
  private val tries = 10

  private val random = SecureRandom()

  /**
    * A code chosen uniformly at random from every possible code: strings of the
    * alphabet are drawn until one is a code, which takes one draw in most cases
    * and more than three almost never.
    */
  private def randomCode(): String = Iterator
    .continually(draw())
    .find(InviteCode.valid)
    .get

  /** A string of a code's length, drawn uniformly from the alphabet. */
  private def draw(): String = String(Array.fill(InviteCode.length)(
    InviteCode.alphabet(random.nextInt(InviteCode.alphabet.length)),
  ))
