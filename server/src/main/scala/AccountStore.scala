package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{AuthRefusal, Principal}
import slick.dbio.DBIO

/**
  * A deleter of user accounts and everything that belongs to them: sessions,
  * recovery codes, owned groups, memberships, invitations, grants and the
  * host's own rows, all in one transaction.
  *
  * @param tables
  *   The tables the accounts are stored in.
  *
  * @param db
  *   The database to run the deletion against.
  *
  * @param users
  *   The store of users.
  *
  * @param groups
  *   The store of groups, which deletes the groups a user owns.
  *
  * @param grants
  *   The store of grants.
  *
  * @param cascade
  *   The hook deleting the host's rows for the user being deleted. It runs
  *   inside the deletion's transaction, before the check for resources the user
  *   alone owns, so that what it deletes is no reason to refuse; a refusal
  *   rolls it back.
  */
final class AccountStore
  (
    tables: AuthTables,
    db: Transactor,
    users: UserStore,
    groups: GroupStore,
    grants: GrantStore,
    cascade: Seq[Principal] => DBIO[Unit],
  ):

  import tables.profile.api.*

  /**
    * Deletes an account and everything that belongs to it.
    *
    * Fails with an [[AuthProblem]], deleting nothing, while the user together
    * with the groups they own are the only owners of a resource (see
    * [[GrantStore.ownedSolelyBy]]). The user's row is locked first, as
    * [[GrantStore.grant]] locks it too, so a concurrent grant to the user
    * either commits first and is withdrawn, or waits.
    *
    * @param user
    *   The identifier of the user to delete.
    */
  def delete(user: Long): IO[Unit] = db.run((for
    _      <- tables.lockUser(user)
    doomed <- doomedWith(user)
    _      <- cascade(Seq(Principal.Person(user)))
    _      <- refuseOrphans(doomed)
    _      <- forget(user)
  yield ()).transactionally)

  /** The principals this deletion removes: the user, and the groups they own. */
  private def doomedWith(user: Long): DBIO[Seq[Principal]] = tables
    .groups
    .filter(_.ownerId === user)
    .map(_.id)
    .result
    .map(Principal.Person(user) +: _.map(Principal.Group(_)))

  private def refuseOrphans(doomed: Seq[Principal]): DBIO[Unit] = grants
    .ownedSolelyBy(doomed)
    .flatMap(orphaned =>
      refuseUnless(
        orphaned.isEmpty,
        AuthRefusal.SoleOwner(orphaned.size),
      ),
    )

  private def forget(user: Long): DBIO[Unit] =
    for
      _ <- groups.forget(user)
      _ <- grants.revokeHeldBy(Seq(Principal.Person(user)))
      _ <- users.delete(user)
    yield ()
