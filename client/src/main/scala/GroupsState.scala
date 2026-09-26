package com.alecdorrington.hecate
package client

import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.{
  GroupDraft, GroupView, Invitation, Invite, Joinable, Membership, User,
}
import com.raquo.laminar.api.L.*
import io.circe.Decoder
import io.circe.parser.decode
import io.laminext.fetch.circe.*
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * The browser-side state of the signed-in user's groups, driving the endpoints
  * of [[com.alecdorrington.hecate.api.GroupApi]]: the groups they own, the
  * groups they belong to, the invitations they have been sent, and the groups
  * they may ask to join. The server returns groups as a flat list; [[forest]]
  * restores the nesting.
  *
  * Every command refetches on completion, and everything refetches whenever the
  * signed-in user changes, so a sign-out empties it and a sign-in fills it. A
  * refused request (as a signed-out viewer's will be) leaves a list empty
  * rather than raising.
  *
  * @param auth
  *   The sign-in state whose user these groups belong to.
  *
  * @param wording
  *   What this state says itself, in the language the host shows. The server's
  *   refusals arrive already worded, in the language of the `language` cookie
  *   the host sets.
  */
final class GroupsState
  (
    auth: AuthState,
    wording: Wording = Wording.english,
  ):

  /**
    * Everything here is bound to the page rather than to a view, since this
    * state outlives any one view and every request it makes must arrive whether
    * or not something is on screen to receive it.
    */
  private given Owner = unsafeWindowOwner

  private val groupsVar: Var[List[GroupView]] = Var(List.empty)

  private val joinedVar: Var[List[Membership]] = Var(List.empty)

  private val invitationsVar: Var[List[Invitation]] = Var(List.empty)

  private val joinableVar: Var[List[Joinable]] = Var(List.empty)

  private val errorVar: Var[Option[String]] = Var(None)

  private val pendingVar: Var[Boolean] = Var(false)

  /**
    * Every group owned by the signed-in user, with its direct members and
    * invitees.
    */
  val groups: Signal[List[GroupView]] = groupsVar.signal

  /**
    * The signed-in user's groups, nested by [[Group.parent]], with siblings
    * ordered by name.
    */
  val forest: Signal[List[GroupTree]] = groupsVar.signal.map(GroupsState.nest)

  /**
    * The groups the signed-in user is a member of but does not own, each with
    * its manager and everyone in it. Their own groups are not repeated here.
    */
  val memberships: Signal[List[Membership]] = joinedVar
    .signal
    .combineWith(groupsVar.signal)
    .mapN((mine, owned) =>
      mine.filterNot(joined => owned.exists(_.group.id == joined.group.id)),
    )

  /** The pending invitations sent to the signed-in user. */
  val invitations: Signal[List[Invitation]] = invitationsVar.signal

  /**
    * The groups the signed-in user may ask to join, and those they have asked
    * to join, ordered by name.
    */
  val joinable: Signal[List[Joinable]] = joinableVar.signal

  /**
    * How many requests to join the signed-in user's groups await their answer,
    * across every group they own.
    */
  val requests: Signal[Int] = groupsVar.signal.map(_.map(_.applicants.size).sum)

  /** The reason the last command was refused, if any. */
  val error: Signal[Option[String]] = errorVar.signal

  /**
    * Whether a command is in flight, for a view that disables its buttons while
    * one is. Only commands count: the refetching that follows one, and the
    * refetching a sign-in sets off, are nobody's to wait for.
    */
  val pending: Signal[Boolean] = pendingVar.signal

  // The lists belong to whoever is signed in, so they follow them: fetched for
  // each user as they sign in, including one signed in already when this state
  // is built (a signal, not its changes, so that a fresh page load with a live
  // session still loads them), and emptied when they sign out.
  locally:
    auth
      .user
      .map(_.map(_.id))
      .distinct
      .foreach(user => if user.isDefined then refresh() else clear())

  /** Empties every list, as nobody is signed in to own them. */
  private def clear(): Unit =
    groupsVar.set(List.empty)
    joinedVar.set(List.empty)
    invitationsVar.set(List.empty)
    joinableVar.set(List.empty)

  /** Refetches every list, emptying those whose requests are refused. */
  def refresh(): Unit =
    fetch[GroupView]("/api/groups", groupsVar)
    fetch[Membership]("/api/groups/mine", joinedVar)
    fetch[Invitation]("/api/invitations", invitationsVar)
    fetch[Joinable]("/api/groups/joinable", joinableVar)

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

  /**
    * Removes one member from a group, cancels one pending invitation, or
    * declines one request to join.
    */
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

  /** Makes the signed-in user a member of a group they own. */
  def join(group: Long): Unit =
    command(Fetch.put(s"/api/groups/$group/membership").text)

  /**
    * Asks to join a group, for its owner to answer. Joins at once a group the
    * user is invited to, or owns.
    */
  def request(group: Long): Unit =
    command(Fetch.put(s"/api/groups/$group/request").text)

  /** Withdraws the signed-in user's request to join a group. */
  def retract(group: Long): Unit =
    command(Fetch.delete(s"/api/groups/$group/request").text)

  /** Admits to one of the user's groups someone who has asked to join it. */
  def admit(group: Long, user: Long): Unit =
    command(Fetch.put(s"/api/groups/$group/members/$user").text)

  /** Makes one of the user's groups public, or private again. */
  def publish(group: Long, public: Boolean): Unit = command(
    Fetch
      .put(
        s"/api/groups/$group/public",
        body = public,
      )
      .text,
  )

  /** Gives one of the user's groups an invite link, unless it has one. */
  def link(group: Long): Unit =
    command(Fetch.put(s"/api/groups/$group/invite-link").text)

  /** Replaces one group's invite link with a new one, ending the old. */
  def relink(group: Long): Unit =
    command(Fetch.post(s"/api/groups/$group/invite-link").text)

  /** Turns off one group's invite link. */
  def unlink(group: Long): Unit =
    command(Fetch.delete(s"/api/groups/$group/invite-link").text)

  /** Discards the last error, so that a corrected form starts clean. */
  def clearError(): Unit = errorVar.set(None)

  /** Replaces one list with what an endpoint returns, or with nothing. */
  private def fetch[X : Decoder](url: String, into: Var[List[X]]): Unit =
    Replied
      .of(Fetch.get(url).text)
      .foreach:
        case Replied.Answered(response) =>
          into.set(decode[List[X]](response.data).getOrElse(List.empty))
        case _ => into.set(List.empty)

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
    pendingVar.set(true)
    errorVar.set(None)
    Replied
      .of(request)
      .foreach: outcome =>
        pendingVar.set(false)
        outcome match
          case Replied.Answered(_)      => errorVar.set(None)
          case Replied.Refused(problem) =>
            errorVar.set(Some(problem))
            refused(problem)
          // Nothing was refused, so nothing is reported to the one caller
          // waiting on a refusal of its own; the reason a request never
          // arrived is this state's own to word.
          case Replied.Unreachable(_) => errorVar.set(Some(wording.unreachable))
        refresh()

object GroupsState:

  /**
    * Rebuilds the nesting of a flat group list, deepest branches included, with
    * siblings ordered by name.
    *
    * No group may contain itself, however deeply: the server refuses any move
    * that would nest one inside its own subtree, so a cycle can never be
    * stored. Nothing here relies on that alone, as a group lost to a cycle
    * would be a group its owner could no longer reach.
    *
    * Visible to the tests, which is the only way to reach it without a server
    * to fetch a list from.
    */
  private[client] def nest(groups: List[GroupView]): List[GroupTree] =
    val ordered = groups.sortBy(_.group.name)
    val known   = ordered.map(_.group.id).toSet
    // A group whose parent is missing is shown at the top level rather than
    // being hidden, and so, after those, is any group a cycle would otherwise
    // bury, so that no group can be lost.
    plant(
      ordered.groupBy(_.group.parent),
    )(ordered.filterNot(_.group.parent.exists(known)) ++ ordered)

  /**
    * Grows a tree from each of the given groups in turn, skipping any that a
    * tree already grown shows, so that every group appears exactly once.
    */
  private def plant
    (children: Map[Option[Long], List[GroupView]])
    (roots: List[GroupView])
    : List[GroupTree] = roots
    .foldLeft((List.empty[GroupTree], Set.empty[Long])):
      case ((forest, shown), view) if shown(view.group.id) => (forest, shown)
      case ((forest, shown), view)                         =>
        val tree = grow(children)(view, Set.empty)
        (tree :: forest, shown ++ tree.flatten.map(_.view.group.id))
    ._1
    .reverse

  /**
    * One group together with every group nested inside it, never descending
    * twice into the same group, so that a cycle could not spin forever.
    */
  private def grow
    (children: Map[Option[Long], List[GroupView]])
    (view: GroupView, enclosing: Set[Long])
    : GroupTree = GroupTree(
    view,
    children
      .getOrElse(Some(view.group.id), List.empty)
      .filterNot(child => enclosing(child.group.id))
      .map(grow(children)(_, enclosing + view.group.id)),
  )

/**
  * One group in the nested view, with the groups inside it.
  *
  * @param view
  *   The group, its direct members and its invitees.
  *
  * @param children
  *   The groups nested directly inside this one, ordered by name.
  */
final case class GroupTree
  (
    view: GroupView,
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
  def ranked(depth: Int = 0): List[(GroupView, Int)] = (view, depth) ::
    children.flatMap(_.ranked(depth + 1))

  /**
    * The distinct members of this group and of every group beneath it, ordered
    * by username. Accepted members only: an invitee is not in a group, so is
    * never counted here.
    */
  def members: List[User] = flatten
    .flatMap(_.view.members)
    .distinctBy(_.id)
    .sortBy(_.username)
