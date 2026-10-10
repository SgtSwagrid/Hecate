package com.alecdorrington.hecate
package server

import Fixtures.{enrol, grant, refusal, register}
import TestDb.tables.profile.api.*
import cats.effect.IO
import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  Access, AuthRefusal, GroupDetails, Holder, Principal, Resource, User,
}
import munit.CatsEffectSuite

/** Who manages a group, by grants over it, and who acts for the system. */
class OwnershipSuite extends CatsEffectSuite:

  import OwnershipSuite.*

  test("a group's creator owns it, and nobody else manages it"):
    withStores("creator"): world =>
      import world.*
      for
        owner    <- register(users, "owner")
        stranger <- register(users, "stranger")
        team     <- groups.create(owner.id, GroupDetails("Team"))
        mine     <- groups.managed(owner.id)
        theirs   <- groups.managed(stranger.id)
        refused  <- refusal(groups.invite(stranger.id, team.id, "owner"))
      yield
        assertEquals(
          mine.map(view => (view.group.id, view.access)),
          List(team.id -> Access.Own),
        )
        assertEquals(theirs, List.empty)
        assertEquals(
          refused,
          said(AuthRefusal.GroupMissing),
        )

  test("a viewer sees who is in a group but changes nothing"):
    withStores("viewer"): world =>
      import world.*
      for
        owner  <- register(users, "owner")
        viewer <- register(users, "viewer")
        member <- register(users, "member")
        team   <- groups.create(owner.id, GroupDetails("Team"))
        _      <- enrol(groups, owner, team.id, member)
        _      <- groups.link(owner.id, team.id)
        _      <- give(
          Resource.group(team.id),
          Principal.Person(viewer.id),
          Access.View,
        )
        seen    <- groups.managed(viewer.id)
        invited <- refusal(groups.invite(viewer.id, team.id, "owner"))
        renamed <- refusal(groups.update(
          viewer.id,
          team.id,
          GroupDetails("Mine now"),
        ))
        deleted <- refusal(groups.delete(viewer.id, team.id))
      yield
        assertEquals(seen.map(_.access), List(Access.View))
        assertEquals(seen.flatMap(_.members), List(member))
        // A viewer may not hand out the link, which would let people in.
        assertEquals(seen.map(_.inviteCode), List(None))
        assertEquals(
          invited,
          said(AuthRefusal.GroupMissing),
        )
        assertEquals(
          renamed,
          said(AuthRefusal.GroupMissing),
        )
        assertEquals(
          deleted,
          said(AuthRefusal.GroupMissing),
        )

  test("an editor changes who is in a group, but cannot delete it"):
    withStores("editor"): world =>
      import world.*
      for
        owner  <- register(users, "owner")
        editor <- register(users, "editor")
        member <- register(users, "member")
        team   <- groups.create(owner.id, GroupDetails("Team"))
        _      <- give(
          Resource.group(team.id),
          Principal.Person(editor.id),
          Access.Edit,
        )
        _ <- enrol(groups, editor, team.id, member)
        _ <- groups.update(
          editor.id,
          team.id,
          GroupDetails("Renamed"),
        )
        _       <- groups.join(editor.id, team.id)
        seen    <- groups.managed(owner.id)
        sent    <- groups.invitations(member.id)
        deleted <- refusal(groups.delete(editor.id, team.id))
        mayAsk  <- groups.mayAddress(editor.id, Principal.Person(member.id))
      yield
        assertEquals(
          seen.map(_.group.name),
          List("Renamed"),
        )
        assertEquals(
          seen.flatMap(_.members).map(_.id).sorted,
          List(editor.id, member.id).sorted,
        )
        assertEquals(sent, List.empty)
        assertEquals(
          deleted,
          said(AuthRefusal.GroupMissing),
        )
        assert(mayAsk)

  test("an invitation says who sent it"):
    withStores("inviter"): world =>
      import world.*
      for
        owner  <- register(users, "owner")
        editor <- register(users, "editor")
        guest  <- register(users, "guest")
        team   <- groups.create(owner.id, GroupDetails("Team"))
        _      <- give(
          Resource.group(team.id),
          Principal.Person(editor.id),
          Access.Edit,
        )
        _    <- groups.invite(editor.id, team.id, guest.username)
        sent <- groups.invitations(guest.id)
      yield assertEquals(
        sent.map(_.inviter),
        List(Some(editor)),
      )

  test("members see every owner of their group"):
    withStores("owners"): world =>
      import world.*
      for
        owner  <- register(users, "owner")
        other  <- register(users, "other")
        member <- register(users, "member")
        team   <- groups.create(owner.id, GroupDetails("Team"))
        _      <- give(
          Resource.group(team.id),
          Principal.Person(other.id),
          Access.Own,
        )
        _ <- give(
          Resource.group(team.id),
          Principal.System,
          Access.Own,
        )
        _      <- enrol(groups, owner, team.id, member)
        joined <- groups.memberships(member.id)
      yield assertEquals(
        joined.map(_.owners),
        List(List(
          Holder(Principal.System, None),
          Holder(
            Principal.Person(other.id),
            Some("other"),
          ),
          Holder(
            Principal.Person(owner.id),
            Some("owner"),
          ),
        )),
      )

  test("a group owned by another is managed by that group's members"):
    withStores("owning-group"): world =>
      import world.*
      for
        head      <- register(users, "head")
        colleague <- register(users, "colleague")
        staff     <- groups.create(head.id, GroupDetails("Staff"))
        _         <- enrol(groups, head, staff.id, colleague)
        rota      <- groups.create(head.id, GroupDetails("Rota"))
        _         <- give(
          Resource.group(rota.id),
          Principal.Group(staff.id),
          Access.Own,
        )
        seen <- groups.managed(colleague.id)
        _    <- groups.update(
          colleague.id,
          rota.id,
          GroupDetails("Weekly rota"),
        )
      yield assertEquals(
        seen.map(view => (view.group.id, view.access)),
        List(rota.id -> Access.Own),
      )

  test("a co-owned group outlives one owner's account"):
    withStores("co-owned"): world =>
      import world.*
      for
        first  <- register(users, "first")
        second <- register(users, "second")
        shared <- groups.create(first.id, GroupDetails("Shared"))
        alone  <- groups.create(first.id, GroupDetails("Alone"))
        _      <- give(
          Resource.group(shared.id),
          Principal.Person(second.id),
          Access.Own,
        )
        _    <- accounts.delete(first.id)
        left <- groups.managed(second.id)
        gone <- groups.names(Seq(alone.id))
        held <- grants.over(Resource.group(shared.id))
      yield
        assertEquals(left.map(_.group.id), List(shared.id))
        assertEquals(gone, Map.empty)
        assertEquals(
          held,
          List(model.Grant(
            Resource.group(shared.id),
            Principal.Person(second.id),
            Access.Own,
          )),
        )

  test("an account takes the groups only its own groups own"):
    withStores("owned-by-owned"): world =>
      import world.*
      for
        owner <- register(users, "owner")
        board <- groups.create(owner.id, GroupDetails("Board"))
        panel <- groups.create(owner.id, GroupDetails("Panel"))
        _     <- give(
          Resource.group(panel.id),
          Principal.Group(board.id),
          Access.Own,
        )
        _    <- db.run(grantsOf(panel.id, Principal.Person(owner.id)).delete)
        _    <- accounts.delete(owner.id)
        gone <- groups.names(Seq(board.id, panel.id))
      yield assertEquals(gone, Map.empty)

  test("deleting a group lifts a nested group someone else owns"):
    withStores("lift"): world =>
      import world.*
      for
        owner  <- register(users, "owner")
        other  <- register(users, "other")
        top    <- groups.create(owner.id, GroupDetails("Top"))
        middle <- groups.create(
          owner.id,
          GroupDetails("Middle", Some(top.id)),
        )
        _ <- give(
          Resource.group(middle.id),
          Principal.Person(other.id),
          Access.Edit,
        )
        // Someone who may change the middle group nests their own inside it.
        theirs <- groups.create(
          other.id,
          GroupDetails("Theirs", Some(middle.id)),
        )
        ours <- groups.create(
          owner.id,
          GroupDetails("Ours", Some(middle.id)),
        )
        _    <- groups.delete(owner.id, middle.id)
        left <- groups.managed(other.id)
        gone <- groups.names(Seq(middle.id, ours.id))
      yield
        assertEquals(gone, Map.empty)
        assertEquals(
          left.map(view => (view.group.id, view.group.parentId)),
          List(theirs.id -> Some(top.id)),
        )

  test(
    "whoever manages an enclosing group may address a nested group's members",
  ):
    withStores("addressers"): world =>
      import world.*
      for
        head   <- register(users, "head")
        leader <- register(users, "leader")
        member <- register(users, "member")
        all    <- groups.create(head.id, GroupDetails("Everyone"))
        _      <- give(
          Resource.group(all.id),
          Principal.Person(leader.id),
          Access.Edit,
        )
        squad <- groups.create(
          leader.id,
          GroupDetails("Squad", Some(all.id)),
        )
        _     <- enrol(groups, leader, squad.id, member)
        found <- groups.addressersOf(member.id)
      yield assertEquals(found, Set(head.id, leader.id))

  test("nesting needs the right to change the parent"):
    withStores("nest"): world =>
      import world.*
      for
        owner    <- register(users, "owner")
        stranger <- register(users, "stranger")
        top      <- groups.create(owner.id, GroupDetails("Top"))
        refused  <- refusal(groups.create(
          stranger.id,
          GroupDetails("Inside", Some(top.id)),
        ))
      yield assertEquals(
        refused,
        said(AuthRefusal.ParentGroupMissing),
      )

  test("only a confirmed operator address acts for the system"):
    withStores("operators", Set("Ops@Example.com")): world =>
      import world.*
      for
        operator <- register(users, "operator")
        pending  <- register(users, "pending")
        _        <- confirm(operator, "ops@example.com")
        acting   <- groups.principalsOf(operator.id)
        others   <- groups.principalsOf(pending.id)
        mayGive  <- groups.mayAddress(operator.id, Principal.System)
        mayNot   <- groups.mayAddress(pending.id, Principal.System)
        listed   <- groups.addressable(operator.id)
      yield
        assertEquals(
          acting,
          List(
            Principal.Person(operator.id),
            Principal.System,
          ),
        )
        assertEquals(
          others,
          List(Principal.Person(pending.id)),
        )
        assert(mayGive)
        assert(!mayNot)
        assertEquals(
          listed.take(2),
          List(
            Principal.Person(operator.id),
            Principal.System,
          ),
        )

  test("whatever the system holds, every operator holds"):
    withStores(
      "system-grant",
      Set("one@example.com", "two@example.com"),
    ): world =>
      import world.*
      val document = Resource("document", 1)
      for
        one     <- register(users, "one")
        two     <- register(users, "two")
        other   <- register(users, "other")
        _       <- confirm(one, "one@example.com")
        _       <- confirm(two, "two@example.com")
        _       <- give(document, Principal.System, Access.Own)
        ones    <- permissions.access(one.id, document)
        theirs  <- permissions.access(other.id, document)
        holders <- permissions.holders(document)
      yield
        assertEquals(ones, Some(Access.Own))
        assertEquals(theirs, None)
        assertEquals(holders, Set(one.id, two.id))

  test("a group the system owns outlives the operator who made it"):
    withStores("system-group", Set("ops@example.com")): world =>
      import world.*
      for
        operator <- register(users, "operator")
        _        <- confirm(operator, "ops@example.com")
        panel    <- groups.create(operator.id, GroupDetails("Panel"))
        _        <- give(
          Resource.group(panel.id),
          Principal.System,
          Access.Own,
        )
        _ <- db.run(
          grantsOf(
            panel.id,
            Principal.Person(operator.id),
          ).delete,
        )
        managing <- groups.managed(operator.id)
        _        <- accounts.delete(operator.id)
        left     <- groups.names(Seq(panel.id))
      yield
        assertEquals(
          managing.map(_.access),
          List(Access.Own),
        )
        assertEquals(left, Map(panel.id -> "Panel"))

object OwnershipSuite:

  /** A refusal as the store reports it, in English. */
  private def said(refusal: AuthRefusal): Option[String] =
    Some(Wording.english.phrase(refusal))

  /** The stores of one throwaway database. */
  private final class World(val db: TestDb, operators: Set[String]):

    val users: UserStore   = UserStore(TestDb.tables, db)
    val grants: GrantStore = GrantStore(TestDb.tables, db)

    val groups: GroupStore =
      GroupStore(TestDb.tables, db, operators = operators)
    val permissions: Permissions = Permissions(groups, grants)

    val accounts: AccountStore = AccountStore(
      TestDb.tables,
      db,
      users,
      groups,
      grants,
      _ => DBIO.unit,
    )

    def give
      (
        resource: Resource,
        principal: Principal,
        access: Access,
      )
      : IO[Unit] = grant(grants, db)(resource, principal, access)

    /** Marks an address as confirmed, as opening its mailed link would. */
    def confirm(user: User, address: String): IO[Unit] = db
      .run(
        TestDb
          .tables
          .users
          .filter(_.id === user.id)
          .map(_.email)
          .update(Some(address)),
      )
      .void

    def grantsOf(group: Long, principal: Principal) = TestDb
      .tables
      .grants
      .filter(row =>
        row.resourceKind === Resource.groupKind && row.resourceId === group &&
        row.principalKind === principal.kind && row.principalId === principal.id,
      )

  private def withStores
    (
      name: String,
      operators: Set[String] = Set.empty,
    )
    (check: World => IO[Unit])
    : IO[Unit] = TestDb
    .open(s"ownership-$name")
    .use(db => check(World(db, operators)))
