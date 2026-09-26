package com.alecdorrington.hecate
package server

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import com.alecdorrington.hecate.api.{AuthApi, LinkApi}
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  AuthRefusal, Caller, Grant, LinkPreview, LinkTarget, Principal, Resource, User,
}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import slick.dbio.DBIO
import sttp.tapir.Endpoint
import sttp.tapir.server.ServerEndpoint

/**
  * The implementation of the API endpoints specified in [[LinkApi]], which show
  * where an invite link leads and follow it. A link to a group joins it; a link
  * to a resource grants the access it carries, never lowering any the follower
  * holds already.
  *
  * The resources are the host application's, so it names them, and it alone can
  * lock one: see `resources`. Links to them are made by the host too, as only
  * it knows who may share one, through [[LinkStore]].
  *
  * A code is short enough to type, and so to guess. Anyone who tries too many
  * codes that lead nowhere in too short a time finds that every code leads
  * nowhere for them until it has passed, including those that lead somewhere,
  * so that guessing cannot be told from being refused. The count is kept in
  * memory, per user, and is lost when the server restarts.
  *
  * @param links
  *   The store the links are kept in.
  *
  * @param groups
  *   The store of groups, which the links to groups join.
  *
  * @param grants
  *   The store of grants, which the links to resources add to.
  *
  * @param db
  *   The database the stores keep their data in.
  *
  * @param auth
  *   The service that resolves session tokens to signed-in users.
  *
  * @param resources
  *   The name of the given resource, locking its row until the transaction
  *   ends, or `None` if there is no such resource, in which case no link leads
  *   to it. Composed into the transaction that follows a link, so that the
  *   access it grants cannot outlive the resource, which the host deletes under
  *   the same lock (see [[GrantStore.revokeAll]]). Defaults to knowing none,
  *   for an application whose links lead only to groups.
  *
  * @param report
  *   Records a failure whose detail must not reach the client, as for
  *   [[GroupService]].
  *
  * @param wording
  *   The wording refusals are written in, as for [[GroupService]].
  *
  * @param affected
  *   Told, once a link is followed, whom that concerns: see [[Affected]]. A
  *   link to a group concerns its owner, its members and the follower, and a
  *   link to a resource changes the grants over it. Defaults to telling nobody.
  *
  * @param guesses
  *   How many codes that lead nowhere one user may try within `window`.
  *
  * @param window
  *   How long a code that led nowhere counts against the user who tried it.
  */
final class LinkService
  (
    links: LinkStore,
    groups: GroupStore,
    grants: GrantStore,
    db: Transactor,
    auth: AuthService,
    resources: Resource => DBIO[Option[String]] = _ => DBIO.successful(None),
    report: Throwable => IO[Unit] = error => Console[IO].printStackTrace(error),
    wording: Option[String] => Wording = _ => Wording.english,
    guesses: Int = 20,
    window: FiniteDuration = 1.hour,
    affected: Affected => IO[Unit] = _ => IO.unit,
  ):

  private val failures = Failures(wording, report)

  /** When each user lately tried a code that led nowhere, in epoch millis. */
  private val misses = Ref.unsafe[IO, Map[Long, List[Long]]](Map.empty)

  private val permissions = Permissions(groups, grants)

  /** An endpoint that shows where one invite link leads. */
  lazy val preview: ServerEndpoint[Any, IO] =
    served(LinkApi.preview)(user => code => glance(user.id, code))

  /** An endpoint that follows one invite link. */
  lazy val follow: ServerEndpoint[Any, IO] =
    served(LinkApi.follow)(user => code => take(user.id, code))

  /** Every endpoint implemented by this service. */
  lazy val api: List[ServerEndpoint[Any, IO]] = List(preview, follow)

  /** Where the link with the given code leads, as the given user sees it. */
  private def glance(user: Long, code: String): IO[LinkPreview] =
    for
      (target, sender) <- found(user, code)
      (name, member)   <- db.run(named(user, target))
      held             <- has(user, target)
    yield LinkPreview(target, name, sender, member || held)

  /**
    * The name of what a link leads to, and whether the user is a member of it
    * when it is a group; a refusal if it no longer exists.
    */
  private def named(user: Long, target: LinkTarget): DBIO[(String, Boolean)] =
    target match
      case LinkTarget.Joining(group) =>
        groups.glance(user, group).flatMap(present(_))
      case LinkTarget.Sharing(resource, _) =>
        resources(resource).flatMap(present(_)).map(_ -> false)

  /** Whether the user holds, over a resource, all a link to it grants. */
  private def has(user: Long, target: LinkTarget): IO[Boolean] = target match
    case LinkTarget.Joining(_)                => IO.pure(false)
    case LinkTarget.Sharing(resource, access) =>
      permissions.access(user, resource).map(_.exists(_.includes(access)))

  /**
    * Gives the user what the link with the given code leads to, rechecking
    * under the lock of its target that the link still leads there.
    */
  private def take(user: Long, code: String): IO[LinkTarget] =
    for
      (target, _) <- found(user, code)
      _           <- db.run(links.atomically(accept(user, code, target)))
      _           <- told(user, target)
    yield target

  /**
    * Tells the host whom following a link concerns: the owner of the group it
    * joined, and the follower, or whoever may see the resource it shared. The
    * link is followed already, so a failure to work that out is reported rather
    * than failing the request.
    */
  private def told(user: Long, target: LinkTarget): IO[Unit] = (target match
    case LinkTarget.Joining(group) => groups
        .surroundings(Seq(group))
        .map(around =>
          Affected.Groups(
            Audience.People(around.owners ++ around.members + user),
            around.enclosing,
          ),
        )
    case LinkTarget.Sharing(resource, _) => IO.pure(Affected.Grants(resource))
  ).flatMap(affected).handleErrorWith(report)

  /** Gives the user what one link leads to, in the caller's transaction. */
  private def accept
    (
      user: Long,
      code: String,
      target: LinkTarget,
    )
    : DBIO[Unit] = target match
    case LinkTarget.Joining(group) => groups.follow(user, group, code)
    case LinkTarget.Sharing(resource, access) =>
      for
        _     <- resources(resource).flatMap(present(_))
        again <- links.find(code)
        _     <-
          if again.flatMap(_._1.target).contains(target) then
            grants.raise(Grant(
              resource,
              Principal.Person(user),
              access,
            ))
          else DBIO.failed(AuthProblem(AuthRefusal.LinkMissing))
      yield ()

  /**
    * Where the link with the given code leads, and who made it, unless the user
    * has lately tried too many codes that led nowhere, in which case it leads
    * nowhere either. A code that leads nowhere counts against them.
    */
  private def found(user: Long, code: String): IO[(LinkTarget, User)] =
    for
      now    <- IO.realTime.map(_.toMillis)
      recent <- misses.modify(LinkService.recent(user, now - window.toMillis))
      link   <-
        if recent >= guesses then IO.pure(None) else db.run(links.find(code))
      leading <- link
        .flatMap((row, sender) => row.target.map(_ -> sender))
        .fold(missed(user, now))(IO.pure)
    yield leading

  /** Counts a code that led nowhere against the user who tried it. */
  private def missed(user: Long, now: Long): IO[Nothing] = misses
    .update(tried => tried.updated(user, now :: tried.getOrElse(user, Nil)))
    .flatMap(_ => IO.raiseError(AuthProblem(AuthRefusal.LinkMissing)))

  /** The value found, or a refusal saying that the link leads nowhere. */
  private def present[X](found: Option[X]): DBIO[X] = found.fold[DBIO[X]](
    DBIO.failed(AuthProblem(AuthRefusal.LinkMissing)),
  )(DBIO.successful)

  /**
    * Serves one endpoint of [[LinkApi]]: the session is resolved to a caller,
    * the request answered, and any failure worded for them.
    */
  private def served[I, O]
    (endpoint: Endpoint[AuthApi.Security, I, String, O, Any])
    (run: Caller => I => IO[O])
    : ServerEndpoint[Any, IO] = endpoint
    .serverSecurityLogic(auth.require)
    .serverLogic(caller =>
      input => failures.attempt(caller.locale)(run(caller)(input)),
    )

object LinkService:

  /**
    * How many codes that led nowhere the given user has tried since the given
    * moment, with every earlier miss forgotten, and every user with none left.
    */
  private def recent
    (user: Long, since: Long)
    (tried: Map[Long, List[Long]])
    : (Map[Long, List[Long]], Int) =
    val kept = tried.getOrElse(user, Nil).filter(_ > since)
    (
      if kept.isEmpty then tried - user else tried.updated(user, kept),
      kept.size,
    )
