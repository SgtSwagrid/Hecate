package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.api.AuthApi
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  Access, Credentials, Grant, GroupDraft, LinkPreview, LinkTarget, Principal,
  Resource, User,
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
  * Tests of the endpoints [[LinkService]] serves: what an invite link shows
  * whoever opens it, and what following it gives them, for a link to a group
  * and a link to a resource.
  */
class LinkServiceSuite extends CatsEffectSuite:

  import LinkServiceSuite.*

  test("following a group's link joins it, once the preview has named it"):
    served(): world =>
      for
        (owner, _)       <- world.signUp("owner")
        (reader, cookie) <- world.signUp("reader")
        team     <- world.groups.create(owner.id, GroupDraft("Book club"))
        code     <- world.groups.link(owner.id, team.id)
        before   <- world.preview(cookie, code.toUpperCase)
        followed <- world.follow(cookie, code)
        after    <- world.preview(cookie, code)
        members  <- world.groups.owned(owner.id)
      yield
        assertEquals(
          before.map(preview =>
            (preview.name, preview.sender.id, preview.already),
          ),
          Right(("Book club", owner.id, false)),
        )
        assertEquals(
          followed,
          Right(LinkTarget.Joining(team.id)),
        )
        assertEquals(after.map(_.already), Right(true))
        assertEquals(
          members.flatMap(_.members.map(_.id)),
          List(reader.id),
        )

  test("a replaced or turned-off link leads nowhere"):
    served(): world =>
      for
        (owner, _)  <- world.signUp("owner")
        (_, cookie) <- world.signUp("reader")
        team        <- world.groups.create(owner.id, GroupDraft("Book club"))
        old         <- world.groups.link(owner.id, team.id)
        fresh       <- world.groups.relink(owner.id, team.id)
        stale       <- world.follow(cookie, old)
        _           <- world.groups.unlink(owner.id, team.id)
        off         <- world.follow(cookie, fresh)
        members     <- world.groups.owned(owner.id)
      yield
        assertEquals(
          stale,
          Left(Wording.english.linkMissing),
        )
        assertEquals(off, Left(Wording.english.linkMissing))
        assertEquals(
          members.flatMap(_.members),
          List.empty[User],
        )

  test("text that could not be a code leads nowhere"):
    served(): world =>
      for
        (_, cookie) <- world.signUp("reader")
        wrong       <- world.preview(cookie, "ab-12")
        short       <- world.preview(cookie, "abc")
      yield
        assertEquals(
          wrong.map(_.name),
          Left(Wording.english.linkMissing),
        )
        assert(
          short.isLeft,
          "a code of three characters was looked up",
        )

  test("following a resource's link grants its access, and never lowers any"):
    served(): world =>
      for
        (owner, _)       <- world.signUp("owner")
        (reader, cookie) <- world.signUp("reader")
        (editor, theirs) <- world.signUp("editor")
        _                <- world.grant(Grant(
          book,
          Principal.Person(editor.id),
          Access.Edit,
        ))
        code    <- world.share(owner, Access.View)
        preview <- world.preview(cookie, code)
        _       <- world.follow(cookie, code)
        _       <- world.follow(theirs, code)
        readers <- world.accessOf(reader)
        editors <- world.accessOf(editor)
      yield
        assertEquals(
          preview.map(found => (found.name, found.target, found.already)),
          Right(("Dune", LinkTarget.Sharing(book, Access.View), false)),
        )
        assertEquals(readers, Some(Access.View))
        assertEquals(editors, Some(Access.Edit))

  test("a link to a resource that is gone leads nowhere, and grants nothing"):
    served(): world =>
      for
        (owner, _)       <- world.signUp("owner")
        (reader, cookie) <- world.signUp("reader")
        code             <- world.share(
          owner,
          Access.View,
          Resource("book", 404),
        )
        followed <- world.follow(cookie, code)
        held     <- world.accessOf(reader, Resource("book", 404))
      yield
        assertEquals(
          followed,
          Left(Wording.english.linkMissing),
        )
        assertEquals(held, None)

  test("withdrawing every grant over a resource turns its link off"):
    served(): world =>
      for
        (owner, _)  <- world.signUp("owner")
        (_, cookie) <- world.signUp("reader")
        code        <- world.share(owner, Access.View)
        _           <- world.db.run(world.grants.revokeAll(book))
        followed    <- world.follow(cookie, code)
      yield assertEquals(
        followed,
        Left(Wording.english.linkMissing),
      )

  test(
    "someone who tries too many codes that lead nowhere is refused every one",
  ):
    served(guesses = 2): world =>
      for
        (owner, _)  <- world.signUp("owner")
        (_, cookie) <- world.signUp("guesser")
        (_, other)  <- world.signUp("reader")
        team        <- world.groups.create(owner.id, GroupDraft("Book club"))
        code        <- world.groups.link(owner.id, team.id)
        wrong = if code == "aaaa1" then "bbbb1" else "aaaa1"
        _          <- world.preview(cookie, wrong)
        _          <- world.preview(cookie, wrong)
        blocked    <- world.preview(cookie, code)
        unaffected <- world.preview(other, code)
      yield
        assertEquals(
          blocked.map(_.name),
          Left(Wording.english.linkMissing),
        )
        assertEquals(
          unaffected.map(_.name),
          Right("Book club"),
        )

object LinkServiceSuite:

  /** The one resource the tests' host application has, a book. */
  private val book = Resource("book", 1)

  /** The names of the host's resources, which a link may lead to. */
  private val names = Map(book -> "Dune")

  /**
    * The stores and endpoints under test, over one fresh database, with the
    * means to act as the users the tests sign up.
    */
  private final class World(val db: TestDb, send: SendRequest):

    val users  = UserStore(TestDb.tables, db)
    val groups = GroupStore(TestDb.tables, db)
    val grants = GrantStore(TestDb.tables, db)
    val links  = LinkStore(TestDb.tables)

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

    /** Where a link leads, as the user with the given session sees it. */
    def preview(cookie: String, code: String): IO[Either[String, LinkPreview]] =
      send(
        basicRequest
          .get(uri"http://test/api/invite-links/$code")
          .cookie(AuthApi.sessionCookie, cookie),
      ).map(read[LinkPreview])

    /** Follows a link as the user with the given session. */
    def follow(cookie: String, code: String): IO[Either[String, LinkTarget]] =
      send(
        basicRequest
          .post(uri"http://test/api/invite-links/$code")
          .cookie(AuthApi.sessionCookie, cookie),
      ).map(read[LinkTarget])

    /** Gives a resource a link granting the given access, as a host would. */
    def share
      (
        creator: User,
        access: Access,
        resource: Resource = book,
      )
      : IO[String] = db.run(links.ensure(
      creator.id,
      LinkTarget.Sharing(resource, access),
    ))

    /** Stores one grant, as a host would. */
    def grant(granted: Grant): IO[Unit] = db.run(grants.grant(granted))

    /** The access one user holds over a resource. */
    def accessOf(user: User, resource: Resource = book): IO[Option[Access]] =
      Permissions(groups, grants).access(user.id, resource)

    /** The decoded body of a reply, or the refusal it carries. */
    private def read[X : io.circe.Decoder]
      (answer: Response[Either[String, String]])
      : Either[String, X] = answer
      .body
      .flatMap(decode[X](_).left.map(_.getMessage))

  /** Runs a check against the endpoints and stores over a fresh database. */
  private def served(guesses: Int = 20)(check: World => IO[Unit]): IO[Unit] =
    TestDb
      .open(s"links-${ java.util.UUID.randomUUID }")
      .use: db =>
        val users   = UserStore(TestDb.tables, db)
        val auth    = AuthService(users)
        val groups  = GroupStore(TestDb.tables, db)
        val grants  = GrantStore(TestDb.tables, db)
        val service = LinkService(
          LinkStore(TestDb.tables),
          groups,
          grants,
          db,
          auth,
          resource => DBIO.successful(names.get(resource)),
          guesses = guesses,
        )
        val backend = TapirStubInterpreter(
          SttpBackendStub[IO, Any](CatsMonadAsyncError[IO]()),
        ).whenServerEndpointsRunLogic(auth.api ++ service.api).backend()
        check(World(db, request => request.send(backend)))

  /** Sends one request to the endpoints under test. */
  private type SendRequest =
    sttp.client3.Request[Either[String, String], Any] => IO[
      Response[Either[String, String]],
    ]
