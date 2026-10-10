package com.alecdorrington.hecate
package server

import Fixtures.{cheap, grant}
import cats.effect.IO
import com.alecdorrington.hecate.model.{
  Access, AuthRefusal, GroupDetails, LinkTarget, Principal, Resource, User,
}
import java.util.Locale
import munit.CatsEffectSuite
import slick.dbio.DBIO

class LinkServiceSuite extends CatsEffectSuite:

  import LinkServiceSuite.*

  test("following a group's link joins it, once the preview has named it"):
    served(): world =>
      for
        owner    <- world.signUp("owner")
        reader   <- world.signUp("reader")
        team     <- world.groups.create(owner.id, GroupDetails("Book club"))
        code     <- world.groups.link(owner.id, team.id)
        before   <- world.service.preview(reader, code.toUpperCase(Locale.ROOT))
        followed <- world.service.follow(reader, code)
        after    <- world.service.preview(reader, code)
        members  <- world.groups.managed(owner.id)
      yield
        assertEquals(
          before.map(preview =>
            (preview.name, preview.inviter.id, preview.already),
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
        owner   <- world.signUp("owner")
        reader  <- world.signUp("reader")
        team    <- world.groups.create(owner.id, GroupDetails("Book club"))
        old     <- world.groups.link(owner.id, team.id)
        fresh   <- world.groups.relink(owner.id, team.id)
        stale   <- world.service.follow(reader, old)
        _       <- world.groups.unlink(owner.id, team.id)
        off     <- world.service.follow(reader, fresh)
        members <- world.groups.managed(owner.id)
      yield
        assertEquals(stale, Left(AuthRefusal.LinkMissing))
        assertEquals(off, Left(AuthRefusal.LinkMissing))
        assertEquals(
          members.flatMap(_.members),
          List.empty[User],
        )

  test("text that could not be a code leads nowhere"):
    served(): world =>
      for
        reader <- world.signUp("reader")
        wrong  <- world.service.preview(reader, "ab-12")
        short  <- world.service.preview(reader, "abc")
        long   <- world.service.preview(reader, "abcd1" * 1000)
      yield
        assertEquals(
          wrong.map(_.name),
          Left(AuthRefusal.LinkMissing),
        )
        assertEquals(
          short.map(_.name),
          Left(AuthRefusal.LinkMissing),
        )
        assertEquals(
          long.map(_.name),
          Left(AuthRefusal.LinkMissing),
        )

  test("following a resource's link grants its access, and never lowers any"):
    served(): world =>
      for
        owner  <- world.signUp("owner")
        reader <- world.signUp("reader")
        editor <- world.signUp("editor")
        _      <- grant(world.grants, world.db)(
          book,
          Principal.Person(editor.id),
          Access.Edit,
        )
        code    <- world.share(owner, Access.View)
        preview <- world.service.preview(reader, code)
        _       <- world.service.follow(reader, code)
        _       <- world.service.follow(editor, code)
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
        owner  <- world.signUp("owner")
        reader <- world.signUp("reader")
        code   <- world.share(
          owner,
          Access.View,
          Resource("book", 404),
        )
        followed <- world.service.follow(reader, code)
        held     <- world.accessOf(reader, Resource("book", 404))
      yield
        assertEquals(
          followed,
          Left(AuthRefusal.LinkMissing),
        )
        assertEquals(held, None)

  test("withdrawing every grant over a resource turns its link off"):
    served(): world =>
      for
        owner    <- world.signUp("owner")
        reader   <- world.signUp("reader")
        code     <- world.share(owner, Access.View)
        _        <- world.db.run(world.grants.revokeOver(book))
        followed <- world.service.follow(reader, code)
      yield assertEquals(
        followed,
        Left(AuthRefusal.LinkMissing),
      )

  test(
    "someone who tries too many codes that lead nowhere is refused every one",
  ):
    served(guesses = 2): world =>
      for
        owner   <- world.signUp("owner")
        guesser <- world.signUp("guesser")
        other   <- world.signUp("reader")
        team    <- world.groups.create(owner.id, GroupDetails("Book club"))
        code    <- world.groups.link(owner.id, team.id)
        wrong = otherThan(code)
        _          <- world.service.preview(guesser, wrong)
        _          <- world.service.preview(guesser, wrong)
        blocked    <- world.service.preview(guesser, code)
        unaffected <- world.service.preview(other, code)
      yield
        assertEquals(
          blocked.map(_.name),
          Left(AuthRefusal.LinkMissing),
        )
        assertEquals(
          unaffected.map(_.name),
          Right("Book club"),
        )

  test("a stranger follows a group's link as a guest, joining it signed in"):
    served(): world =>
      for
        owner <- world.signUp("owner")
        team  <- world.groups.create(owner.id, GroupDetails("Book club"))
        code  <- world.groups.link(owner.id, team.id)
        made  <- world
          .service
          .welcome(
            code.toUpperCase(Locale.ROOT),
            "Reader",
          )
        (guest, target, session) = made.toOption.get
        found   <- world.auth.current(Some(session.token))
        members <- world.groups.managed(owner.id)
      yield
        assertEquals(
          (guest.username, guest.guest, target),
          ("Reader", true, LinkTarget.Joining(team.id)),
        )
        assertEquals(found, Right(Some(guest)))
        assertEquals(
          members.flatMap(_.members.map(_.id)),
          List(guest.id),
        )

  test("a stranger follows a resource's link as a guest, granted its access"):
    served(): world =>
      for
        owner <- world.signUp("owner")
        code  <- world.share(owner, Access.Edit)
        made  <- world.service.welcome(code, "Reader")
        held  <- world.accessOf(made.toOption.get._1)
      yield
        assertEquals(
          made.map(_._2),
          Right(LinkTarget.Sharing(book, Access.Edit)),
        )
        assertEquals(held, Some(Access.Edit))

  test("a link that leads nowhere, or to nothing, makes no guest"):
    served(): world =>
      for
        owner <- world.signUp("owner")
        code  <- world.share(owner, Access.View)
        wrong = otherThan(code)
        gone <- world.share(
          owner,
          Access.View,
          Resource("book", 404),
        )
        missing  <- world.service.welcome(wrong, "Reader")
        orphaned <- world.service.welcome(gone, "Reader")
        found    <- world.users.findByUsername("Reader")
      yield
        assertEquals(
          missing.map(_._1),
          Left(AuthRefusal.LinkMissing),
        )
        assertEquals(
          orphaned.map(_._1),
          Left(AuthRefusal.LinkMissing),
        )
        assertEquals(found, None)

  test("strangers who try too many codes that lead nowhere pause guests alone"):
    served(strangerGuesses = 2): world =>
      for
        owner <- world.signUp("owner")
        other <- world.signUp("reader")
        code  <- world.share(owner, Access.View)
        wrong = otherThan(code)
        _          <- world.service.welcome(wrong, "Guesser")
        _          <- world.service.welcome(wrong, "Guesser")
        paused     <- world.service.welcome(code, "Reader")
        unaffected <- world.service.follow(other, code)
      yield
        assertEquals(
          paused.map(_._1),
          Left(AuthRefusal.GuestsPaused),
        )
        assertEquals(
          unaffected,
          Right(LinkTarget.Sharing(book, Access.View)),
        )

  test("strangers are held to their own allowance, never to a user's"):
    served(guesses = 1, strangerGuesses = 3): world =>
      for
        owner <- world.signUp("owner")
        code  <- world.share(owner, Access.View)
        wrong = otherThan(code)
        _      <- world.service.welcome(wrong, "Guesser")
        _      <- world.service.welcome(wrong, "Guesser")
        within <- world.service.welcome(code, "Reader")
      yield assertEquals(
        within.map(_._2),
        Right(LinkTarget.Sharing(book, Access.View)),
      )

  test("nobody follows a link as a guest where guests are not let in"):
    served(guests = false): world =>
      for
        owner   <- world.signUp("owner")
        code    <- world.share(owner, Access.View)
        refused <- world.service.welcome(code, "Reader")
      yield
        assertEquals(
          refused.map(_._1),
          Left(AuthRefusal.NoGuests),
        )
        assert(!world.service.welcoming)

object LinkServiceSuite:

  private val book = Resource("book", 1)

  private val names = Map(book -> "Dune")

  private def otherThan(code: String): String =
    if code == "aaaa1" then "bbbb1" else "aaaa1"

  private final class World
    (
      val db: TestDb,
      val service: LinkService,
      val auth: AuthService,
    ):

    val users = UserStore(TestDb.tables, db)

    val groups = GroupStore(TestDb.tables, db)
    val grants = GrantStore(TestDb.tables, db)
    val links  = LinkStore(TestDb.tables)

    def signUp(name: String): IO[User] = Fixtures.signUp(auth, name).map(_._1)

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

    def accessOf(user: User, resource: Resource = book): IO[Option[Access]] =
      Permissions(groups, grants).access(user.id, resource)

  /** Runs a check over a fresh database, letting guests in by default. */
  private def served
    (
      guesses: Int = 20,
      strangerGuesses: Int = 100,
      guests: Boolean = true,
    )
    (check: World => IO[Unit])
    : IO[Unit] = TestDb
    .open("links")
    .use: db =>
      val auth = AuthService(
        UserStore(TestDb.tables, db),
        cheap.copy(guests = guests),
      )
      check(World(
        db,
        LinkService(
          LinkStore(TestDb.tables),
          GroupStore(TestDb.tables, db),
          GrantStore(TestDb.tables, db),
          db,
          resource => DBIO.successful(names.get(resource)),
          guesses = guesses,
          guests = Some(auth),
          strangerGuesses = strangerGuesses,
        ),
        auth,
      ))
