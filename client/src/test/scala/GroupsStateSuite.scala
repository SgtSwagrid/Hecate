package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.model.{Group, OwnedGroup, User}
import munit.FunSuite

class GroupsStateSuite extends FunSuite:

  import GroupsStateSuite.*

  test("a flat list is nested by parent"):
    val forest = GroupsState.nest(List(
      view(1, "Sales"),
      view(2, "Retail", parent = 1),
      view(3, "Front desk", parent = 2),
    ))
    assertEquals(names(forest), List("Sales"))
    assertEquals(
      names(forest.head.children),
      List("Retail"),
    )
    assertEquals(
      names(forest.head.children.head.children),
      List("Front desk"),
    )

  test("siblings are ordered by name, at every depth"):
    val forest = GroupsState.nest(List(
      view(1, "Sales"),
      view(2, "Zebra"),
      view(3, "Apples"),
      view(4, "Yak", parent = 1),
      view(5, "Ants", parent = 1),
    ))
    assertEquals(
      names(forest),
      List("Apples", "Sales", "Zebra"),
    )
    assertEquals(
      names(forest(1).children),
      List("Ants", "Yak"),
    )

  test("a group whose parent is not in the list is shown at the top"):
    val forest = GroupsState.nest(List(
      view(1, "Orphan", parent = 99),
      view(2, "Sales"),
    ))
    assertEquals(names(forest), List("Orphan", "Sales"))

  test("every group given appears exactly once"):
    val groups = List(
      view(1, "Sales"),
      view(2, "Retail", parent = 1),
      view(3, "Front desk", parent = 2),
      view(4, "Chess club"),
    )
    val shown = GroupsState.nest(groups).flatMap(_.flatten).map(_.view.group.id)
    assertEquals(
      shown.sorted,
      groups.map(_.group.id).sorted,
    )
    assertEquals(shown.distinct.size, shown.size)

  test("a tree outlines each group with its depth, in depth-first order"):
    val forest = GroupsState.nest(List(
      view(1, "Sales"),
      view(2, "Retail", parent = 1),
      view(3, "Front desk", parent = 2),
      view(4, "Wholesale", parent = 1),
    ))
    assertEquals(
      forest.head.outline().map((group, depth) => (group.group.name, depth)),
      List(
        ("Sales", 0),
        ("Retail", 1),
        ("Front desk", 2),
        ("Wholesale", 1),
      ),
    )

  test("a tree's members are those of the groups beneath it, each once"):
    val forest = GroupsState.nest(List(
      view(1, "Sales", members = List(alice)),
      view(
        2,
        "Retail",
        parent = 1,
        members = List(bob, alice),
      ),
      view(3, "Chess club", members = List(carol)),
    ))
    assertEquals(
      forest.find(_.view.group.id == 1).get.members.map(_.username),
      List("alice", "bob"),
    )

  test("a cycle buries no group, and does not spin"):
    // The server never stores a cycle, but the client must not lose a group to
    // one.
    val forest = GroupsState.nest(List(
      view(1, "Sales", parent = 2),
      view(2, "Retail", parent = 1),
      view(3, "Chess club"),
    ))
    val shown = forest.flatMap(_.flatten).map(_.view.group.id).distinct
    assertEquals(shown.sorted, List(1L, 2L, 3L))

  test("invitees are never counted as members"):
    val forest = GroupsState.nest(List(view(
      1,
      "Sales",
      members = List(alice),
      invitees = List(bob),
    )))
    assertEquals(forest.head.members, List(alice))

object GroupsStateSuite:

  private val alice = User(1, "alice")
  private val bob   = User(2, "bob")
  private val carol = User(3, "carol")

  private def view
    (
      id: Long,
      name: String,
      parent: Long = 0,
      members: List[User] = List.empty,
      invitees: List[User] = List.empty,
    )
    : OwnedGroup = OwnedGroup(
    Group(
      id,
      name,
      Option.when(parent != 0)(parent),
    ),
    members,
    invitees,
  )

  private def names(forest: List[GroupTree]): List[String] =
    forest.map(_.view.group.name)
