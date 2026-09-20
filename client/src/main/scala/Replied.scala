package com.alecdorrington.hecate
package client

import com.raquo.laminar.api.L.*
import io.laminext.fetch.circe.*

/**
  * What became of one request, as every state here needs to read it: the three
  * outcomes must not be conflated, since a server that refuses has told the
  * user something, and a server that was never reached has not.
  */
enum Replied:

  /**
    * The server answered, and did not refuse.
    *
    * @param response
    *   The reply, for whatever is to be read out of it.
    */
  case Answered(response: FetchResponse[String])

  /**
    * The server refused, in the language the request asked for.
    *
    * @param problem
    *   The refusal, as the reader is to be shown it.
    */
  case Refused(problem: String)

  /**
    * The request never reached the server, so nothing at all is known: the user
    * is no more signed out, and no more refused, than before they asked.
    *
    * @param detail
    *   Whatever the browser said, which is not a sentence for a reader.
    */
  case Unreachable(detail: String)

object Replied:

  /**
    * The outcome of one request, once it has one.
    *
    * @param request
    *   The request, as the stream of its one reply.
    *
    * @return
    *   A stream of the one outcome.
    */
  def of(request: EventStream[FetchResponse[String]]): EventStream[Replied] =
    request
      .map(response =>
        if response.status >= 400 then Refused(response.data)
        else Answered(response),
      )
      .recover { case failure => Some(Unreachable(failure.getMessage)) }
