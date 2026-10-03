package com.alecdorrington.hecate
package server

import Fixtures.register
import cats.effect.IO
import com.alecdorrington.hecate.api.Protocol
import com.alecdorrington.hecate.model.{AuthRefusal, GroupDetails, Invitee}
import munit.CatsEffectSuite

class GroupServiceSuite extends CatsEffectSuite:

  private val overlong = "a" * (Protocol.maxNameLength + 1)

  private def serving
    (check: (GroupService, GroupStore, UserStore) => IO[Unit])
    : IO[Unit] = TestDb
    .groups("group-service")
    .use((groups, users) => check(GroupService(groups), groups, users))

  test("a group's name longer than the protocol allows is refused, unstored"):
    serving: (service, groups, users) =>
      for
        owner   <- register(users, "owner")
        created <- service.create(owner, GroupDetails(overlong))
        stored  <- groups.owned(owner.id)
      yield
        assertEquals(
          created.map(_.id),
          Left(AuthRefusal.TooLong(Protocol.maxNameLength)),
        )
        assertEquals(stored, Nil)

  test("an invitation naming nobody the protocol allows is refused"):
    serving: (service, groups, users) =>
      for
        owner   <- register(users, "owner")
        team    <- groups.create(owner.id, GroupDetails("Team"))
        invited <- service.invite(owner, team.id, Invitee(overlong))
      yield assertEquals(
        invited.map(_.id),
        Left(AuthRefusal.TooLong(Protocol.maxNameLength)),
      )

  test("a store's refusal is answered as a value, not a failure"):
    serving: (service, _, users) =>
      for
        stranger <- register(users, "stranger")
        deleted  <- service.delete(stranger, 404)
      yield assertEquals(deleted, Left(AuthRefusal.GroupMissing))
