package com.alecdorrington.hecate
package server

import cats.effect.{IO, Ref}
import com.alecdorrington.hecate.api.AuthApi
import com.alecdorrington.hecate.model.{
  Access, Credentials, GroupDraft, LinkTarget, PasswordChange, Resource, User,
}
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import slick.dbio.DBIO
import sttp.client3.{basicRequest, Response, UriContext}
import sttp.client3.impl.cats.CatsMonadAsyncError
import sttp.client3.testing.SttpBackendStub
import sttp.tapir.server.stub.TapirStubInterpreter

/**
  * Tests of whom the services say each change concerns, through their
  * `affected` hook: nobody who sees nothing of it, and nobody left out who did,
  * before the change or after it.
  */
class AffectedSuite extends CatsEffectSuite:

  import AffectedSuite.*

  test("joining or leaving a group concerns its members, who see one another"):
    served: world =>
      for
        (owner, _)       <- world.signUp("owner")
        (member, _)      <- world.signUp("member")
        (leaver, cookie) <- world.signUp("leaver")
        (_, outsider)    <- world.signUp("outsider")
        team             <- world.groups.create(owner.id, GroupDraft("Team"))
        _                <- world.join(owner, team.id, member)
        _                <- world.join(owner, team.id, leaver)
        other            <- world.groups.create(owner.id, GroupDraft("Other"))
        code             <- world.groups.link(owner.id, other.id)
        _                <- world.follow(outsider, code)
        _                <- world.reported
        _                <- world.leave(cookie, team.id)
        told             <- world.reported
      yield assertEquals(
        told,
        List(Affected.Groups(
          Audience.People(Set(owner.id, member.id, leaver.id)),
          Set(team.id),
        )),
      )

  test("joining a group concerns its owner and the joiner, and its enclosure"):
    served: world =>
      for
        (owner, _)       <- world.signUp("owner")
        (other, _)       <- world.signUp("other")
        (joiner, cookie) <- world.signUp("joiner")
        school           <- world.groups.create(owner.id, GroupDraft("School"))
        _    <- world.groups.invite(owner.id, school.id, other.username)
        team <- world
          .groups
          .create(
            owner.id,
            GroupDraft("Team", Some(school.id)),
          )
        code <- world.groups.link(owner.id, team.id)
        _    <- world.reported
        _    <- world.follow(cookie, code)
        told <- world.reported
      yield assertEquals(
        told,
        List(Affected.Groups(
          Audience.People(Set(owner.id, joiner.id)),
          Set(team.id, school.id),
        )),
      )

  test("renaming a group concerns whoever sees it, and not whoever is in it"):
    served: world =>
      for
        (owner, cookie) <- world.signUp("owner")
        (member, _)     <- world.signUp("member")
        (invitee, _)    <- world.signUp("invitee")
        _               <- world.signUp("stranger")
        school          <- world.groups.create(owner.id, GroupDraft("School"))
        team            <- world
          .groups
          .create(
            owner.id,
            GroupDraft("Team", Some(school.id)),
          )
        _ <- world.join(owner, school.id, member)
        _ <- world.groups.invite(owner.id, team.id, invitee.username)
        _ <- world.reported
        _ <- world.rename(
          cookie,
          team.id,
          GroupDraft("Squad", Some(school.id)),
        )
        told <- world.reported
      yield assertEquals(
        told,
        List(Affected.Groups(
          Audience.People(Set(owner.id, member.id, invitee.id)),
          Set(team.id, school.id),
        )),
      )

  test("a group anyone may find concerns everyone, as does leaving it public"):
    served: world =>
      for
        (owner, cookie) <- world.signUp("owner")
        club            <- world.groups.create(owner.id, GroupDraft("Club"))
        _               <- world.publish(cookie, club.id, public = true)
        opened          <- world.reported
        _               <- world.publish(cookie, club.id, public = false)
        closed          <- world.reported
      yield
        assertEquals(
          opened,
          List(Affected.Groups(Audience.Everyone, Set(club.id))),
        )
        assertEquals(
          closed,
          List(Affected.Groups(Audience.Everyone, Set(club.id))),
        )

  test("deleting a group concerns whoever was in it and its subgroups"):
    served: world =>
      for
        (owner, cookie) <- world.signUp("owner")
        (member, _)     <- world.signUp("member")
        school          <- world.groups.create(owner.id, GroupDraft("School"))
        team            <- world
          .groups
          .create(
            owner.id,
            GroupDraft("Team", Some(school.id)),
          )
        _    <- world.join(owner, team.id, member)
        _    <- world.reported
        _    <- world.delete(cookie, school.id)
        told <- world.reported
      yield assertEquals(
        told,
        List(Affected.Groups(
          Audience.People(Set(owner.id, member.id)),
          Set(team.id, school.id),
        )),
      )

  test("following a link to a resource changes its grants"):
    served: world =>
      for
        (owner, _)  <- world.signUp("owner")
        (_, cookie) <- world.signUp("reader")
        code        <- world
          .db
          .run(
            world
              .links
              .ensure(
                owner.id,
                LinkTarget.Sharing(book, Access.View),
              ),
          )
        _    <- world.follow(cookie, code)
        told <- world.reported
      yield assertEquals(told, List(Affected.Grants(book)))

  test("a new password concerns the account's own sessions"):
    served: world =>
      for
        (user, cookie) <- world.signUp("user")
        _              <- world.changePassword(cookie)
        told           <- world.reported
      yield assertEquals(told, List(Affected.Account(user.id)))

  test("a refused change concerns nobody"):
    served: world =>
      for
        (owner, _)        <- world.signUp("owner")
        (_, strangerSide) <- world.signUp("stranger")
        club              <- world.groups.create(owner.id, GroupDraft("Club"))
        _                 <- world.reported
        _                 <- world.rename(
          strangerSide,
          club.id,
          GroupDraft("Mine"),
        )
        told <- world.reported
      yield assertEquals(told, List.empty)

object AffectedSuite:

  /** The one resource the tests' host application has, a book. */
  private val book = Resource("book", 1)

  /**
    * The stores and endpoints under test, over one fresh database, with what
    * the services have reported so far and the means to act as the users the
    * tests sign up.
    */
  private final class World
    (
      val db: TestDb,
      send: SendRequest,
      told: Ref[IO, List[Affected]],
    ):

    val users  = UserStore(TestDb.tables, db)
    val groups = GroupStore(TestDb.tables, db)
    val links  = LinkStore(TestDb.tables)

    /** What has been reported since this was last asked, oldest first. */
    def reported: IO[List[Affected]] = told.getAndSet(Nil).map(_.reverse)

    /** Registers a user, yielding them and their session cookie. */
    def signUp(name: String): IO[(User, String)] = send(
      basicRequest
        .post(uri"http://test/api/auth/register")
        .body(Credentials(name, "hunter2222").asJson.noSpaces),
    ).map(answer =>
      (
        answer
          .body
          .flatMap(decode[User](_).left.map(_.getMessage))
          .toOption
          .get,
        answer.unsafeCookies.find(_.name == AuthApi.sessionCookie).get.value,
      ),
    )

    /**
      * Invites a user to a group, straight through the store, and has them
      * accept.
      */
    def join(owner: User, group: Long, member: User): IO[Unit] =
      for
        _     <- groups.invite(owner.id, group, member.username)
        found <- groups.invitations(member.id)
        _     <- groups.accept(member.id, found.head.id)
      yield ()

    /** Follows a link as the user with the given session. */
    def follow(cookie: String, code: String): IO[Unit] = send(
      basicRequest
        .post(uri"http://test/api/invite-links/$code")
        .cookie(AuthApi.sessionCookie, cookie),
    ).void

    /** Leaves a group as the user with the given session. */
    def leave(cookie: String, group: Long): IO[Unit] = send(
      basicRequest
        .delete(uri"http://test/api/groups/$group/membership")
        .cookie(AuthApi.sessionCookie, cookie),
    ).void

    /** Renames or moves a group as the user with the given session. */
    def rename
      (
        cookie: String,
        group: Long,
        draft: GroupDraft,
      )
      : IO[Unit] = send(
      basicRequest
        .put(uri"http://test/api/groups/$group")
        .cookie(AuthApi.sessionCookie, cookie)
        .body(draft.asJson.noSpaces),
    ).void

    /** Makes a group public or private as the user with the given session. */
    def publish
      (
        cookie: String,
        group: Long,
        public: Boolean,
      )
      : IO[Unit] = send(
      basicRequest
        .put(uri"http://test/api/groups/$group/public")
        .cookie(AuthApi.sessionCookie, cookie)
        .body(public.asJson.noSpaces),
    ).void

    /** Deletes a group as the user with the given session. */
    def delete(cookie: String, group: Long): IO[Unit] = send(
      basicRequest
        .delete(uri"http://test/api/groups/$group")
        .cookie(AuthApi.sessionCookie, cookie),
    ).void

    /** Changes the password of the user with the given session. */
    def changePassword(cookie: String): IO[Unit] = send(
      basicRequest
        .put(uri"http://test/api/auth/password")
        .cookie(AuthApi.sessionCookie, cookie)
        .body(PasswordChange("hunter2222", "hunter3333").asJson.noSpaces),
    ).void

  /** Runs a check against the endpoints and stores over a fresh database. */
  private def served(check: World => IO[Unit]): IO[Unit] = TestDb
    .open(s"affected-${ java.util.UUID.randomUUID }")
    .use: db =>
      for
        told <- Ref.of[IO, List[Affected]](Nil)
        tell    = (affected: Affected) => told.update(affected :: _)
        users   = UserStore(TestDb.tables, db)
        auth    = AuthService(users, affected = tell)
        groups  = GroupStore(TestDb.tables, db)
        grants  = GrantStore(TestDb.tables, db)
        service = GroupService(groups, auth, affected = tell)
        links   = LinkService(
          LinkStore(TestDb.tables),
          groups,
          grants,
          db,
          auth,
          resource => DBIO.successful(Option.when(resource == book)("Dune")),
          affected = tell,
        )
        backend = TapirStubInterpreter(
          SttpBackendStub[IO, Any](CatsMonadAsyncError[IO]()),
        ).whenServerEndpointsRunLogic(auth.api ++ service.api ++ links.api)
          .backend()
        _ <- check(World(
          db,
          request => request.send(backend),
          told,
        ))
      yield ()

  /** Sends one request to the endpoints under test. */
  private type SendRequest =
    sttp.client3.Request[Either[String, String], Any] => IO[
      Response[Either[String, String]],
    ]
