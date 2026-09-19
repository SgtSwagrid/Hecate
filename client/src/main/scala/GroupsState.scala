package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.model.{
  Group, GroupDraft, GroupView, Invitation, Invite, User,
}
import com.raquo.laminar.api.L.*
import io.circe.Decoder
import io.circe.parser.decode
import io.laminext.fetch.circe.*
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * The browser-side state of the signed-in user's groups, driving the endpoints
  * of [[com.alecdorrington.hecate.api.GroupApi]]: the groups they own, the
  * groups they belong to, and the invitations they have been sent. The server
  * returns groups as a flat list; [[forest]] restores the nesting.
  *
  * Every command refetches on completion, and everything refetches whenever the
  * signed-in user changes, so a sign-out empties it and a sign-in fills it. A
  * refused request (as a signed-out viewer's will be) leaves a list empty
  * rather than raising.
  *
  * @param auth
  *   The sign-in state whose user these groups belong to.
  */
final class GroupsState(auth: AuthState):

  private val stored: Var[List[GroupView]] = Var(List.empty)

  private val joined: Var[List[Group]] = Var(List.empty)

  private val invited: Var[List[Invitation]] = Var(List.empty)

  private val failure: Var[Option[String]] = Var(None)

  /**
    * Every group owned by the signed-in user, with its direct members and
    * invitees.
    */
  val groups: Signal[List[GroupView]] = stored.signal

  /**
    * The signed-in user's groups, nested by [[Group.parent]], with siblings
    * ordered by name.
    */
  val forest: Signal[List[GroupTree]] = stored.signal.map(GroupsState.nest)

  /**
    * The groups the signed-in user is a member of but does not own. Their own
    * groups are not repeated here.
    */
  val memberships: Signal[List[Group]] = joined
    .signal
    .combineWith(stored.signal)
    .mapN((mine, owned) =>
      mine.filterNot(group => owned.exists(_.group.id == group.id)),
    )

  /** The pending invitations sent to the signed-in user. */
  val invitations: Signal[List[Invitation]] = invited.signal

  /** The reason the last command was refused, if any. */
  val error: Signal[Option[String]] = failure.signal

  // The lists belong to whoever is signed in, so they follow them: fetched for
  // each user as they sign in, including one signed in already when this state
  // is built (a signal, not its changes, so that a fresh page load with a live
  // session still loads them), and emptied when they sign out.
  locally:
    given Owner = unsafeWindowOwner
    auth
      .user
      .map(_.map(_.id))
      .distinct
      .foreach(user => if user.isDefined then refresh() else clear())

  /** Empties every list, as nobody is signed in to own them. */
  private def clear(): Unit =
    stored.set(List.empty)
    joined.set(List.empty)
    invited.set(List.empty)

  /** Refetches every list, emptying those whose requests are refused. */
  def refresh(): Unit =
    fetch[GroupView]("/api/groups", stored)
    fetch[Group]("/api/groups/mine", joined)
    fetch[Invitation]("/api/invitations", invited)

  /** Creates a group, nested under the given parent when there is one. */
  def create(name: String, parent: Option[Long] = None): Unit = command(
    Fetch
      .post(
        "/api/groups",
        body = GroupDraft(name, parent),
      )
      .text,
  )

  /**
    * Renames one group, leaving its place in the hierarchy alone.
    *
    * @param refused
    *   Called with the reason, should this rename be refused, and only for this
    *   rename: a view editing the name can restore it without being disturbed
    *   by some other command's failure.
    */
  def rename
    (
      group: GroupView,
      name: String,
      refused: String => Unit = _ => (),
    )
    : Unit = command(
    Fetch
      .put(
        s"/api/groups/${ group.group.id }",
        body = GroupDraft(name, group.group.parent),
      )
      .text,
    refused,
  )

  /** Moves one group under the given parent, or to the top level. */
  def move(group: GroupView, parent: Option[Long]): Unit = command(
    Fetch
      .put(
        s"/api/groups/${ group.group.id }",
        body = GroupDraft(group.group.name, parent),
      )
      .text,
  )

  /** Deletes one group, together with every group nested beneath it. */
  def delete(id: Long): Unit = command(Fetch.delete(s"/api/groups/$id").text)

  /** Invites the user with the given username to one group. */
  def invite(group: Long, username: String): Unit = command(
    Fetch
      .post(
        s"/api/groups/$group/invitations",
        body = Invite(username),
      )
      .text,
  )

  /** Removes one member from a group, or cancels one pending invitation. */
  def withdraw(group: Long, user: Long): Unit =
    command(Fetch.delete(s"/api/groups/$group/members/$user").text)

  /** Accepts one invitation, joining its group. */
  def accept(invitation: Long): Unit =
    command(Fetch.post(s"/api/invitations/$invitation/accept").text)

  /** Declines one invitation, deleting it. */
  def decline(invitation: Long): Unit =
    command(Fetch.post(s"/api/invitations/$invitation/decline").text)

  /** Withdraws the signed-in user from a group they belong to. */
  def leave(group: Long): Unit =
    command(Fetch.delete(s"/api/groups/$group/membership").text)

  /** Discards the last error, so that a corrected form starts clean. */
  def clearError(): Unit = failure.set(None)

  /** Replaces one list with what an endpoint returns, or with nothing. */
  private def fetch[X : Decoder](url: String, into: Var[List[X]]): Unit =
    given Owner = unsafeWindowOwner
    Fetch
      .get(url)
      .text
      .map(response =>
        if response.status >= 400 then List.empty
        else decode[List[X]](response.data).getOrElse(List.empty),
      )
      .recover { case _ => Some(List.empty) }
      .foreach(into.set)

  /**
    * Runs one command, recording any refusal and refetching the lists.
    *
    * @param refused
    *   Called with the reason, should the server refuse this command. Not
    *   called when the request fails to reach the server.
    */
  private def command
    (
      request: EventStream[FetchResponse[String]],
      refused: String => Unit = _ => (),
    )
    : Unit =
    given Owner = unsafeWindowOwner
    failure.set(None)
    // Each outcome carries its problem, if any, and whether the server answered.
    request
      .map(response =>
        (Option.when(response.status >= 400)(response.data), true),
      )
      .recover { case error => Some((Some(error.getMessage), false)) }
      .foreach: (problem, answered) =>
        failure.set(problem)
        if answered then problem.foreach(refused)
        refresh()

object GroupsState:

  /**
    * Rebuilds the nesting of a flat group list, deepest branches included, with
    * siblings ordered by name.
    */
  private def nest(groups: List[GroupView]): List[GroupTree] =
    val children                         = groups.groupBy(_.group.parent)
    val known                            = groups.map(_.group.id).toSet
    def grow(view: GroupView): GroupTree = GroupTree(
      view,
      children
        .getOrElse(Some(view.group.id), List.empty)
        .sortBy(_.group.name)
        .map(grow),
    )
    // A group whose parent is missing is shown at the top level rather than
    // being hidden, so that no group can be lost.
    groups
      .filterNot(_.group.parent.exists(known))
      .sortBy(_.group.name)
      .map(grow)

/**
  * One group in the nested view, with the groups inside it.
  *
  * @param group
  *   The group, its direct members and its invitees.
  *
  * @param children
  *   The groups nested directly inside this one, ordered by name.
  */
final case class GroupTree
  (
    group: GroupView,
    children: List[GroupTree],
  ):

  /** This group and every group beneath it, in depth-first order. */
  def flatten: List[GroupTree] = this :: children.flatMap(_.flatten)

  /**
    * This group and every group beneath it, in depth-first order, each paired
    * with its depth below the top level, for rendering the tree as an indented
    * list.
    *
    * @param depth
    *   The depth of this group, from `0` at the top level.
    */
  def ranked(depth: Int = 0): List[(GroupView, Int)] = (group, depth) ::
    children.flatMap(_.ranked(depth + 1))

  /**
    * The distinct members of this group and of every group beneath it, ordered
    * by username. Accepted members only: an invitee is not in a group, so is
    * never counted here.
    */
  def members: List[User] = flatten
    .flatMap(_.group.members)
    .distinctBy(_.id)
    .sortBy(_.username)
