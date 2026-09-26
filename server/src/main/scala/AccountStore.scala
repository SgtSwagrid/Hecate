package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{AuthRefusal, Principal}
import slick.dbio.DBIO

/**
  * Deletes user accounts, together with everything that belongs to them: their
  * sessions and recovery codes, the groups they own (with those groups'
  * memberships, invitations and grants), their own memberships and invitations,
  * the grants they hold, and whatever the host application attaches to them.
  * All in one transaction, so that nothing can be left pointing at an account
  * that no longer exists.
  *
  * @param tables
  *   The tables the accounts are stored in.
  *
  * @param db
  *   The database to run the deletion against.
  *
  * @param users
  *   The store of users, which removes the account itself.
  *
  * @param groups
  *   The store of groups, which deletes the groups a user owns in the same way
  *   it deletes any other group, host cascade included.
  *
  * @param grants
  *   The store of grants, which says what the user would leave ownerless and
  *   withdraws what they hold.
  *
  * @param cascade
  *   Deletes whatever the host application attaches to the given principals:
  *   here, the one user being deleted. The same shape as the cascade given to
  *   [[GroupStore]], which fires it for the groups the user owned. Run first,
  *   inside the deletion's transaction: before the check for anything the user
  *   alone owns, so that a resource it deletes with the account, grants and
  *   all, is no reason to refuse, and before the user's groups and the account
  *   itself are removed. A refusal rolls it back with the rest.
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
    * Deletes one account and everything that belongs to it, unless the user,
    * together with the groups they own, is the only owner of some resource:
    * deleting them then would leave it owned by nobody, and so invisible to
    * everyone, along with whatever depends on it. Such a deletion is refused
    * with an [[AuthProblem]], and nothing is deleted; the user must first pass
    * those resources on, or delete them.
    *
    * What counts as another owner is any principal holding `Own` that is not
    * being deleted here, a group with no members included: see
    * [[GrantStore.soleOwnerOf]] for what that does and does not promise.
    *
    * The groups a user owns are not themselves a reason to refuse. A group has
    * exactly one owner by construction, and is deleted with them, which is why
    * their `Own` grants count for nothing here. Nor is anything the host's
    * cascade removes with the account, as it runs before the check.
    *
    * The user's own row is locked first, and so is every grant written to a
    * person ([[GrantStore.grant]]), so that a grant made to the user while they
    * are being deleted either commits before this and is withdrawn with the
    * rest, or waits until the account is gone.
    */
  def delete(user: Long): IO[Unit] = db.run((for
    _      <- lock(user)
    doomed <- doomedWith(user)
    _      <- cascade(Seq(Principal.Person(user)))
    _      <- refuseOrphans(doomed)
    _      <- remove(user)
  yield ()).transactionally)

  /**
    * Locks the user's row until the transaction ends, so that a grant written
    * to them takes its turn with their deletion rather than outliving it.
    */
  private def lock(user: Long): DBIO[Unit] = tables
    .users
    .filter(_.id === user)
    .forUpdate
    .result
    .map(_ => ())

  /** The principals this deletion removes: the user, and the groups they own. */
  private def doomedWith(user: Long): DBIO[Seq[Principal]] = tables
    .groups
    .filter(_.owner === user)
    .map(_.id)
    .result
    .map(Principal.Person(user) +: _.map(Principal.Group(_)))

  /**
    * Fails the transaction, deleting nothing, if removing these principals
    * together would leave a resource with no owner at all.
    */
  private def refuseOrphans(doomed: Seq[Principal]): DBIO[Unit] = grants
    .soleOwnerOf(doomed)
    .flatMap(orphaned =>
      if orphaned.isEmpty then DBIO.successful(())
      else DBIO.failed(AuthProblem(AuthRefusal.SoleOwner(orphaned.size))),
    )

  /**
    * Removes everything else that belongs to the user, the host's cascade
    * having run already, and then the user.
    */
  private def remove(user: Long): DBIO[Unit] =
    for
      _ <- groups.forget(user)
      _ <- grants.revokeHeldBy(Seq(Principal.Person(user)))
      _ <- users.remove(user)
    yield ()
