package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.effect.std.Console
import com.alecdorrington.hecate.api.{AuthApi, GroupApi}
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.Caller
import sttp.tapir.Endpoint
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

  private val failures = Failures(wording, report)

  /** An endpoint that lists the signed-in user's groups, with members. */
  lazy val list: ServerEndpoint[Any, IO] =
    served(GroupApi.list)(user => _ => groups.owned(user.id))

  /** An endpoint that lists the groups the signed-in user is a member of. */
  lazy val mine: ServerEndpoint[Any, IO] =
    served(GroupApi.mine)(user => _ => groups.memberships(user.id))

  /** An endpoint that withdraws the signed-in user from one group. */
  lazy val leave: ServerEndpoint[Any, IO] =
    served(GroupApi.leave)(user => id => groups.leave(user.id, id))

  /** An endpoint that stores a new group for the signed-in user. */
  lazy val create: ServerEndpoint[Any, IO] =
    served(GroupApi.create)(user => draft => groups.create(user.id, draft))

  /** An endpoint that renames and/or moves one stored group. */
  lazy val update: ServerEndpoint[Any, IO] = served(GroupApi.update)(user =>
    (id, draft) => groups.update(user.id, id, draft),
  )

  /** An endpoint that deletes one stored group and its subgroups. */
  lazy val delete: ServerEndpoint[Any, IO] =
    served(GroupApi.delete)(user => id => groups.delete(user.id, id))

  /** An endpoint that invites one user to one group. */
  lazy val invite: ServerEndpoint[Any, IO] = served(GroupApi.invite)(user =>
    (id, request) => groups.invite(user.id, id, request.username.trim),
  )

  /** An endpoint that removes one member or pending invitee from one group. */
  lazy val withdraw: ServerEndpoint[Any, IO] = served(GroupApi.withdraw)(user =>
    (id, member) => groups.withdraw(user.id, id, member),
  )

  /** An endpoint that lists the invitations sent to the signed-in user. */
  lazy val invitations: ServerEndpoint[Any, IO] =
    served(GroupApi.invitations)(user => _ => groups.invitations(user.id))

  /** An endpoint that accepts one of the signed-in user's invitations. */
  lazy val accept: ServerEndpoint[Any, IO] =
    served(GroupApi.accept)(user => id => groups.accept(user.id, id))

  /** An endpoint that declines one of the signed-in user's invitations. */
  lazy val decline: ServerEndpoint[Any, IO] =
    served(GroupApi.decline)(user => id => groups.decline(user.id, id))

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
    * Serves one endpoint of [[GroupApi]]: the session is resolved to a caller,
    * the store is asked, and any failure is worded for them. Every endpoint
    * here is this and nothing else.
    *
    * @param endpoint
    *   The endpoint to serve.
    *
    * @param run
    *   What the caller's request asks of the store.
    *
    * @return
    *   The endpoint, with its logic.
    */
  private def served[I, O]
    (endpoint: Endpoint[AuthApi.Security, I, String, O, Any])
    (run: Caller => I => IO[O])
    : ServerEndpoint[Any, IO] = endpoint
    .serverSecurityLogic(auth.require)
    .serverLogic(caller => input => attempt(caller)(run(caller)(input)))

  /**
    * Runs a store action for one caller, converting any failure to an error
    * message in their language, exactly as every other service here does. See
    * [[Failures]].
    *
    * A host reporting its own failures can answer with the same fixed phrase,
    * as it is part of the wording.
    */
  private def attempt[X](caller: Caller)(action: IO[X]): IO[Either[String, X]] =
    failures.attempt(caller.locale)(action)
