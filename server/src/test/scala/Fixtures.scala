package com.alecdorrington.hecate
package server

import cats.effect.IO
import com.alecdorrington.hecate.api.AuthApi
import com.alecdorrington.hecate.model.{
  Access, Credentials, Grant, Principal, Resource, User,
}
import io.circe.Decoder
import io.circe.parser.decode
import io.circe.syntax.*
import sttp.client3.{basicRequest, Request, Response, UriContext}
import sttp.client3.impl.cats.CatsMonadAsyncError
import sttp.client3.testing.SttpBackendStub
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub.TapirStubInterpreter

/**
  * What the suites here set up alike: users, memberships and grants made
  * straight through the stores, and endpoints served in memory rather than over
  * a socket.
  */
object Fixtures:

  /** Registers a user straight through the store. */
  def newUser(users: UserStore, name: String): IO[User] = users
    .register(name, "hash")
    .map(_.get)

  /** Invites a user to a group straight through the store, and has them accept. */
  def enrol
    (
      groups: GroupStore,
      owner: User,
      group: Long,
      member: User,
    )
    : IO[Unit] =
    for
      _    <- groups.invite(owner.id, group, member.username)
      sent <- groups.invitations(member.id)
      _    <- groups.accept(
        member.id,
        sent.find(_.group.id == group).get.id,
      )
    yield ()

  /** Stores one grant, running the store's action to completion. */
  def give
    (grants: GrantStore, db: TestDb)
    (
      resource: Resource,
      principal: Principal,
      access: Access,
    )
    : IO[Unit] = db.run(grants.grant(Grant(resource, principal, access)))

  /** The message a failed action was refused with. */
  def refusal(action: IO[?]): IO[Option[String]] = action
    .attempt
    .map(_.left.toOption.map(_.getMessage))

  /** Sends one request to the endpoints under test. */
  type SendRequest =
    Request[Either[String, String], Any] => IO[Response[Either[String, String]]]

  /**
    * Serves the given endpoints in memory, yielding a way to send them one
    * request.
    */
  def serve(endpoints: List[ServerEndpoint[Any, IO]]): SendRequest =
    val backend = TapirStubInterpreter(
      SttpBackendStub[IO, Any](CatsMonadAsyncError[IO]()),
    ).whenServerEndpointsRunLogic(endpoints).backend()
    request => request.send(backend)

  /** A request registering one account. */
  def register
    (username: String, password: String)
    : Request[Either[String, String], Any] = basicRequest
    .post(uri"http://test/api/auth/register")
    .body(Credentials(username, password).asJson.noSpaces)

  /**
    * Registers a user through the endpoints, yielding them and their session
    * cookie.
    */
  def signUp(send: SendRequest, name: String): IO[(User, String)] = send(
    register(name, "hunter2222"),
  ).map(answer =>
    (
      read[User](answer).toOption.get,
      answer.unsafeCookies.find(_.name == AuthApi.sessionCookie).get.value,
    ),
  )

  /** The decoded body of a reply, or the refusal it carries. */
  def read[X : Decoder]
    (answer: Response[Either[String, String]])
    : Either[String, X] = answer
    .body
    .flatMap(decode[X](_).left.map(_.getMessage))
