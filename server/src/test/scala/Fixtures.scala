package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.model.{
  Access, Credentials, Grant, Invitation, Principal, Resource, User,
}

object Fixtures:

  /** A policy hashing passwords under few iterations, for speed. */
  val cheap: AuthPolicy = AuthPolicy(hashIterations = 1000)

  def register(users: UserStore, name: String): IO[User] = users
    .register(name, "hash")
    .map(_.get)

  def signUp(auth: AuthService, name: String): IO[(User, String)] = auth
    .register(Credentials(name, "hunter2222"))
    .map(registered =>
      val (user, session) = registered.toOption.get
      (user, session.token),
    )

  /** The invitation the given user holds to the given group. */
  def invitationTo
    (
      groups: GroupStore,
      user: User,
      group: Long,
    )
    : IO[Invitation] = groups
    .invitations(user.id)
    .map(_.find(_.group.id == group).get)

  def enrol
    (
      groups: GroupStore,
      owner: User,
      group: Long,
      member: User,
    )
    : IO[Unit] =
    for
      _    <- groups.invite(owner.id, group, member.username)
      sent <- invitationTo(groups, member, group)
      _    <- groups.accept(member.id, sent.id)
    yield ()

  def grant
    (grants: GrantStore, db: TestDb)
    (
      resource: Resource,
      principal: Principal,
      access: Access,
    )
    : IO[Unit] = db.run(grants.grant(Grant(resource, principal, access)))

  def refusal(action: IO[?]): IO[Option[String]] = action
    .attempt
    .map(_.left.toOption.map(_.getMessage))
