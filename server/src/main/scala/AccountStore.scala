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
  *   [[GroupStore]], which fires it for the groups the user owned. Run inside
  *   the deletion's transaction, before the account itself is removed.
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
    * The groups a user owns are not themselves a reason to refuse. A group has
    * exactly one owner by construction, and is deleted with them, which is why
    * their `Own` grants count for nothing here.
    *
    * The user's own row is locked first, and so is every grant written to a
    * person ([[GrantStore.grant]]), so that a grant made to the user while they
    * are being deleted either commits before this and is withdrawn with the
    * rest, or waits until the account is gone.
    */
  def delete(user: Long): IO[Unit] = db.run((for
    _     <- tables.users.filter(_.id === user).forUpdate.result
    owned <- tables.groups.filter(_.owner === user).map(_.id).result
    doomed = Principal.Person(user) +: owned.map(Principal.Group(_))
    orphaned <- grants.soleOwnerOf(doomed)
    _        <-
      if orphaned.isEmpty then DBIO.successful(())
      else DBIO.failed(AuthProblem(AuthRefusal.SoleOwner(orphaned.size)))
    _ <- groups.forget(user)
    _ <- cascade(Seq(Principal.Person(user)))
    _ <- grants.revokeHeldBy(Seq(Principal.Person(user)))
    _ <- users.remove(user)
  yield ()).transactionally)
