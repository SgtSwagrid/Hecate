package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.effect.std.Console
import com.alecdorrington.hecate.api.GroupApi
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.Caller
import sttp.tapir.server.ServerEndpoint

/**
  * The implementation of the API endpoints specified in [[GroupApi]], which
  * manage the signed-in user's groups, their memberships, and invitations to
  * join them.
  *
  * @param groups
  *   The store the groups are kept in.
  *
  * @param auth
  *   The service that resolves session tokens to signed-in users.
  *
  * @param report
  *   Records a failure whose detail must not reach the client. Defaults to the
  *   console, so that no failure goes unrecorded; a host application with its
  *   own logging should pass its logger instead.
  *
  * @param wording
  *   The wording refusals are written in, chosen by the language a request asks
  *   for, or `None` when it asks for none. Defaults to the library's English
  *   for every request; a host application should pass the same choice it gives
  *   its [[AuthService]].
  */
final class GroupService
  (
    groups: GroupStore,
    auth: AuthService,
    report: Throwable => IO[Unit] = error => Console[IO].printStackTrace(error),
    wording: Option[String] => Wording = _ => Wording.english,
  ):

  /** An endpoint that lists the signed-in user's groups, with members. */
  lazy val list: ServerEndpoint[Any, IO] = GroupApi
    .list
    .serverSecurityLogic(auth.require)
    .serverLogic(user => _ => attempt(user)(groups.list(user.id)))

  /** An endpoint that lists the groups the signed-in user is a member of. */
  lazy val mine: ServerEndpoint[Any, IO] = GroupApi
    .mine
    .serverSecurityLogic(auth.require)
    .serverLogic(user => _ => attempt(user)(groups.memberships(user.id)))

  /** An endpoint that withdraws the signed-in user from one group. */
  lazy val leave: ServerEndpoint[Any, IO] = GroupApi
    .leave
    .serverSecurityLogic(auth.require)
    .serverLogic(user => id => attempt(user)(groups.leave(user.id, id)))

  /** An endpoint that stores a new group for the signed-in user. */
  lazy val create: ServerEndpoint[Any, IO] = GroupApi
    .create
    .serverSecurityLogic(auth.require)
    .serverLogic(user => draft => attempt(user)(groups.create(user.id, draft)))

  /** An endpoint that renames and/or moves one stored group. */
  lazy val update: ServerEndpoint[Any, IO] = GroupApi
    .update
    .serverSecurityLogic(auth.require)
    .serverLogic(user =>
      (id, draft) => attempt(user)(groups.update(user.id, id, draft)),
    )

  /** An endpoint that deletes one stored group and its subgroups. */
  lazy val delete: ServerEndpoint[Any, IO] = GroupApi
    .delete
    .serverSecurityLogic(auth.require)
    .serverLogic(user => id => attempt(user)(groups.delete(user.id, id)))

  /** An endpoint that invites one user to one group. */
  lazy val invite: ServerEndpoint[Any, IO] = GroupApi
    .invite
    .serverSecurityLogic(auth.require)
    .serverLogic(user =>
      (id, request) =>
        attempt(user)(groups.invite(user.id, id, request.username.trim)),
    )

  /** An endpoint that removes one member or pending invitee from one group. */
  lazy val withdraw: ServerEndpoint[Any, IO] = GroupApi
    .withdraw
    .serverSecurityLogic(auth.require)
    .serverLogic(user =>
      (id, member) => attempt(user)(groups.withdraw(user.id, id, member)),
    )

  /** An endpoint that lists the invitations sent to the signed-in user. */
  lazy val invitations: ServerEndpoint[Any, IO] = GroupApi
    .invitations
    .serverSecurityLogic(auth.require)
    .serverLogic(user => _ => attempt(user)(groups.invitations(user.id)))

  /** An endpoint that accepts one of the signed-in user's invitations. */
  lazy val accept: ServerEndpoint[Any, IO] = GroupApi
    .accept
    .serverSecurityLogic(auth.require)
    .serverLogic(user => id => attempt(user)(groups.accept(user.id, id)))

  /** An endpoint that declines one of the signed-in user's invitations. */
  lazy val decline: ServerEndpoint[Any, IO] = GroupApi
    .decline
    .serverSecurityLogic(auth.require)
    .serverLogic(user => id => attempt(user)(groups.decline(user.id, id)))

  /** Every endpoint implemented by this service. */
  lazy val api: List[ServerEndpoint[Any, IO]] = List(
    list,
    mine,
    leave,
    create,
    update,
    delete,
    invite,
    withdraw,
    invitations,
    accept,
    decline,
  )

  /**
    * Runs a store action for one caller, converting any failure to an error
    * message in their language. An [[AuthProblem]] is worded for them, as its
    * refusal is theirs to understand. Any other failure is handed to [[report]]
    * and answered only with the wording's fixed phrase, as a driver's message
    * can include the failing SQL.
    *
    * A host reporting its own failures can answer with the same fixed phrase,
    * as it is part of the wording.
    */
  private def attempt[X](caller: Caller)(action: IO[X]): IO[Either[String, X]] =
    action
      .map(Right(_))
      .handleErrorWith(error => explain(caller, error).map(Left(_)))

  /** The message shown to one caller for one failure. */
  private def explain(caller: Caller, error: Throwable): IO[String] =
    val words = wording(caller.locale)
    error match
      case problem: AuthProblem => IO.pure(words.phrase(problem.refusal))
      case _                    => report(error).as(words.requestFailed)
