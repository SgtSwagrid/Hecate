package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.effect.std.Console
import com.alecdorrington.hecate.api.{AuthApi, GroupApi}
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.Caller
import sttp.tapir.server.ServerEndpoint

/**
  * The implementation of the API endpoints specified in [[GroupApi]], which
  * manage the signed-in user's groups, their memberships, and the invitations,
  * requests and links by which people join them.
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
  *
  * @param affected
  *   Told, once each change is committed, whom it concerns: see [[Affected]]. A
  *   change to who is in, invited to or asking to join a group, or to its
  *   invite link, concerns its owner, its members and that person; a change to
  *   the group itself concerns everyone who saw anything of it before or sees
  *   anything of it after, and everyone at all when it was or is public.
  *   Defaults to telling nobody.
  */
final class GroupService
  (
    groups: GroupStore,
    auth: AuthService,
    report: Throwable => IO[Unit] = error => Console[IO].printStackTrace(error),
    wording: Option[String] => Wording = _ => Wording.english,
    affected: Affected => IO[Unit] = _ => IO.unit,
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
    servedJoining(GroupApi.leave)(groups.leave)

  /** An endpoint that makes the signed-in user a member of a group they own. */
  lazy val join: ServerEndpoint[Any, IO] =
    servedJoining(GroupApi.join)(groups.join)

  /** An endpoint that lists the groups the signed-in user may ask to join. */
  lazy val joinable: ServerEndpoint[Any, IO] =
    served(GroupApi.joinable)(user => _ => groups.joinable(user.id))

  /** An endpoint that asks to join one group. */
  lazy val request: ServerEndpoint[Any, IO] =
    servedJoining(GroupApi.request)(groups.request)

  /** An endpoint that withdraws a request to join one group. */
  lazy val retract: ServerEndpoint[Any, IO] =
    servedJoining(GroupApi.retract)(groups.retract)

  /** An endpoint that makes one group public or private. */
  lazy val publish: ServerEndpoint[Any, IO] = served(GroupApi.publish)(user =>
    (id, public) =>
      reshaping(IO.pure(Seq(id)))(groups.publish(user.id, id, public))(_ =>
        Seq(id),
      ),
  )

  /** An endpoint that gives one group an invite link unless it has one. */
  lazy val link: ServerEndpoint[Any, IO] =
    servedJoining(GroupApi.link)(groups.link)

  /** An endpoint that replaces one group's invite link with a new one. */
  lazy val relink: ServerEndpoint[Any, IO] =
    servedJoining(GroupApi.relink)(groups.relink)

  /** An endpoint that turns off one group's invite link. */
  lazy val unlink: ServerEndpoint[Any, IO] =
    servedJoining(GroupApi.unlink)(groups.unlink)

  /** An endpoint that stores a new group for the signed-in user. */
  lazy val create: ServerEndpoint[Any, IO] = served(GroupApi.create)(user =>
    draft =>
      reshaping(IO.pure(Seq.empty))(groups.create(user.id, draft))(created =>
        Seq(created.id),
      ),
  )

  /** An endpoint that renames and/or moves one stored group. */
  lazy val update: ServerEndpoint[Any, IO] = served(GroupApi.update)(user =>
    (id, draft) =>
      reshaping(IO.pure(Seq(id)))(groups.update(user.id, id, draft))(_ =>
        Seq(id),
      ),
  )

  /** An endpoint that deletes one stored group and its subgroups. */
  lazy val delete: ServerEndpoint[Any, IO] = served(GroupApi.delete)(user =>
    id =>
      reshaping(groups.subtreeOf(user.id, id))(groups.delete(user.id, id))(_ =>
        Seq.empty,
      ),
  )

  /** An endpoint that invites one user to one group. */
  lazy val invite: ServerEndpoint[Any, IO] = served(GroupApi.invite)(user =>
    (id, request) =>
      groups
        .invite(user.id, id, request.username.trim)
        .flatTap(invited => told(joined(Seq(id), invited.id))),
  )

  /** An endpoint that admits to one group a user who has asked to join it. */
  lazy val admit: ServerEndpoint[Any, IO] = served(GroupApi.admit)(user =>
    (id, member) => joining(Seq(id), member)(groups.admit(user.id, id, member)),
  )

  /** An endpoint that removes one member, invitee or applicant from one group. */
  lazy val withdraw: ServerEndpoint[Any, IO] = served(GroupApi.withdraw)(user =>
    (id, member) =>
      joining(Seq(id), member)(groups.withdraw(user.id, id, member)),
  )

  /** An endpoint that lists the invitations sent to the signed-in user. */
  lazy val invitations: ServerEndpoint[Any, IO] =
    served(GroupApi.invitations)(user => _ => groups.invitations(user.id))

  /** An endpoint that accepts one of the signed-in user's invitations. */
  lazy val accept: ServerEndpoint[Any, IO] = served(GroupApi.accept)(user =>
    id =>
      groups
        .invitedTo(user.id, id)
        .flatMap(joining(_, user.id)(groups.accept(user.id, id))),
  )

  /** An endpoint that declines one of the signed-in user's invitations. */
  lazy val decline: ServerEndpoint[Any, IO] = served(GroupApi.decline)(user =>
    id =>
      groups
        .invitedTo(user.id, id)
        .flatMap(joining(_, user.id)(groups.decline(user.id, id))),
  )

  /** Every endpoint implemented by this service. */
  lazy val api: List[ServerEndpoint[Any, IO]] = List(
    list,
    mine,
    joinable,
    leave,
    join,
    request,
    retract,
    create,
    update,
    delete,
    publish,
    link,
    relink,
    unlink,
    invite,
    admit,
    withdraw,
    invitations,
    accept,
    decline,
  )

  /**
    * Runs a change to who is in, invited to or asking to join the given groups,
    * or to their invite links, and once it is made, tells their owners, their
    * members, who see one another, and the person it was about. Nobody else's
    * view of the groups changes with it.
    */
  private def joining[X]
    (changed: Seq[Long], person: Long)
    (change: IO[X])
    : IO[X] = change.flatTap(_ => told(joined(changed, person)))

  /** Whom a change to who is in the given groups concerns. */
  private def joined(changed: Seq[Long], person: Long): IO[Affected] = groups
    .surroundings(changed)
    .map(_.joined(person))

  /**
    * Runs a change to groups themselves: their names, nesting, visibility or
    * existence. Once it is made, tells everyone who saw anything of the groups
    * it affects as they were, asked about before the change, or sees anything
    * of them as they are, asked about after it.
    *
    * @param before
    *   The groups the change affects, as they stand before it.
    *
    * @param change
    *   The change.
    *
    * @param after
    *   The groups the change affected, as they stand after it.
    */
  private def reshaping[X]
    (before: IO[Seq[Long]])
    (change: IO[X])
    (after: X => Seq[Long])
    : IO[X] =
    for
      earlier <- before.flatMap(groups.surroundings)
      result  <- change
      _       <- told(
        groups
          .surroundings(after(result))
          .map(later =>
            Affected.Groups(
              if earlier.public || later.public then Audience.Everyone
              else Audience.People(earlier.people ++ later.people),
              earlier.enclosing ++ later.enclosing,
            ),
          ),
      )
    yield result

  /**
    * Tells the host whom a change concerns. The change is made already, so a
    * failure to work that out is reported rather than failing the request.
    */
  private def told(concerned: IO[Affected]): IO[Unit] = concerned
    .flatMap(affected)
    .handleErrorWith(report)

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
    (endpoint: AuthApi.Secured[I, O])
    (run: Caller => I => IO[O])
    : ServerEndpoint[Any, IO] = endpoint
    .serverSecurityLogic(auth.require)
    .serverLogic(caller =>
      input => failures.attempt(caller.locale)(run(caller)(input)),
    )

  /**
    * Serves one endpoint of [[GroupApi]] that changes one group on the caller's
    * own behalf, run as [[joining]] runs a change about the caller.
    *
    * @param endpoint
    *   The endpoint to serve, whose input is the group.
    *
    * @param change
    *   The change to make, given the identifiers of the caller and the group.
    *
    * @return
    *   The endpoint, with its logic.
    */
  private def servedJoining[O]
    (endpoint: AuthApi.Secured[Long, O])
    (change: (Long, Long) => IO[O])
    : ServerEndpoint[Any, IO] = served(endpoint)(user =>
    id => joining(Seq(id), user.id)(change(user.id, id)),
  )
