package com.alecdorrington.hecate
package server
package tapir

import cats.effect.IO
import com.alecdorrington.hecate.model.Welcome
import com.alecdorrington.hecate.tapir.LinkApi
import sttp.tapir.server.ServerEndpoint

/**
  * A server of the endpoints in [[LinkApi]], backed by a [[LinkService]].
  *
  * @param links
  *   The service that answers the requests.
  *
  * @param auth
  *   The endpoints that resolve sessions to callers and word refusals.
  */
final class LinkEndpoints(links: LinkService, auth: AuthEndpoints):

  /** The server endpoint for [[LinkApi.preview]]. */
  lazy val preview: ServerEndpoint[Any, IO] =
    auth.served(LinkApi.preview)(user => code => links.preview(user, code))

  /** The server endpoint for [[LinkApi.follow]]. */
  lazy val follow: ServerEndpoint[Any, IO] =
    auth.served(LinkApi.follow)(user => code => links.follow(user, code))

  /** The server endpoint for [[LinkApi.welcome]]. */
  lazy val welcome: ServerEndpoint[Any, IO] = LinkApi
    .welcome
    .serverLogic((code, guest, locale) =>
      auth
        .worded(locale)(links.welcome(code, guest.name))
        .map(_.map((user, target, session) =>
          (Welcome(user, target), AuthEndpoints.cookie(session)),
        )),
    )

  /**
    * The endpoints to serve. Following a link as a guest is left out where the
    * service admits no guests.
    */
  lazy val api: List[ServerEndpoint[Any, IO]] = List(preview, follow) ++
    Option.when(links.welcoming)(welcome)
