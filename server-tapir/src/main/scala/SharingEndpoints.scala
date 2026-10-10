package com.alecdorrington.hecate
package server
package tapir

import cats.effect.IO
import com.alecdorrington.hecate.model.Resource
import com.alecdorrington.hecate.tapir.SharingApi
import sttp.tapir.server.ServerEndpoint

/**
  * A server of the endpoints in [[SharingApi]] for one kind of resource, backed
  * by a [[SharingService]].
  *
  * @param sharing
  *   The service that answers the requests.
  *
  * @param kind
  *   The kind of resource, as the host names it.
  *
  * @param endpoints
  *   The endpoints of that kind, from [[SharingApi.of]].
  *
  * @param auth
  *   The endpoints that resolve sessions to callers and word refusals.
  */
final class SharingEndpoints
  (
    sharing: SharingService,
    kind: String,
    endpoints: SharingApi.Endpoints,
    auth: AuthEndpoints,
  ):

  /** The server endpoint for [[SharingApi.Endpoints.list]]. */
  lazy val list: ServerEndpoint[Any, IO] = auth.served(endpoints.list)(user =>
    id => sharing.list(user, Resource(kind, id)),
  )

  /** The server endpoint for [[SharingApi.Endpoints.grant]]. */
  lazy val grant: ServerEndpoint[Any, IO] = auth.served(endpoints.grant)(user =>
    (id, principal, level) =>
      sharing.grant(
        user,
        Resource(kind, id),
        principal,
        level,
      ),
  )

  /** The server endpoint for [[SharingApi.Endpoints.revoke]]. */
  lazy val revoke: ServerEndpoint[Any, IO] =
    auth.served(endpoints.revoke)(user =>
      (id, principal) => sharing.revoke(user, Resource(kind, id), principal),
    )

  /** The server endpoint for [[SharingApi.Endpoints.link]]. */
  lazy val link: ServerEndpoint[Any, IO] = auth.served(endpoints.link)(user =>
    id => sharing.link(user, Resource(kind, id)),
  )

  /** The server endpoint for [[SharingApi.Endpoints.setLink]]. */
  lazy val setLink: ServerEndpoint[Any, IO] = auth.served(endpoints.setLink)(
    user => (id, level) => sharing.setLink(user, Resource(kind, id), level),
  )

  /** The server endpoint for [[SharingApi.Endpoints.relink]]. */
  lazy val relink: ServerEndpoint[Any, IO] = auth.served(endpoints.relink)(
    user => id => sharing.relink(user, Resource(kind, id)),
  )

  /** The server endpoint for [[SharingApi.Endpoints.unlink]]. */
  lazy val unlink: ServerEndpoint[Any, IO] = auth.served(endpoints.unlink)(
    user => id => sharing.unlink(user, Resource(kind, id)),
  )

  /**
    * The endpoints of the resource's grants alone, for a kind whose invite
    * links are served otherwise, as a group's are by [[GroupEndpoints]].
    */
  lazy val grants: List[ServerEndpoint[Any, IO]] = List(list, grant, revoke)

  /** The endpoints to serve. */
  lazy val api: List[ServerEndpoint[Any, IO]] = List(
    list,
    grant,
    revoke,
    link,
    setLink,
    relink,
    unlink,
  )
