package com.alecdorrington.hecate
package model

import io.circe.{Decoder, DecodingFailure, Encoder, JsonObject}
import io.circe.syntax.*

/**
  * One part of a resource as a particular reader may see it: shown to them,
  * absent because it does not exist, or withheld because it exists but they may
  * not see it yet. The read-side counterpart of [[Permitted]].
  *
  * Absent and withheld must never be conflated, as each means something
  * different to the reader: a review that was never written is absent, while
  * one held back until its publication is withheld. Encoding either as an empty
  * or default value would make a withheld part read as a missing one.
  *
  * Withholding is for parts of a resource the reader may otherwise see. A
  * resource the reader may not see at all is reported as not existing, never as
  * a resource whose every part is withheld, which would disclose that it
  * exists.
  *
  * A gated part is stored and sent under an explicitly spelled `state`, never
  * under the name of its case, so that renaming a case in the code cannot
  * change what is sent.
  */
enum Gated[+X]:

  /**
    * A part that exists and that the reader may see.
    *
    * @param value
    *   The part itself.
    */
  case Shown(value: X)

  /** A part that does not exist, such as a review that was never written. */
  case Absent

  /** A part that exists, but that the reader may not see yet. */
  case Withheld

object Gated:

  /** The `state` a [[Gated.Shown]] part is sent under. Never change it. */
  val shownState = "shown"

  /** The `state` a [[Gated.Absent]] part is sent under. Never change it. */
  val absentState = "absent"

  /** The `state` a [[Gated.Withheld]] part is sent under. Never change it. */
  val withheldState = "withheld"

  /**
    * Shows a piece of text, treating blank text as absent rather than as shown
    * but empty, so that a part left blank is never mistaken for one withheld.
    *
    * @param text
    *   The text of the part.
    *
    * @return
    *   A gated part: absent if the text is blank, and shown otherwise.
    */
  def text(text: String): Gated[String] =
    if text.isBlank then Absent else Shown(text)

  /**
    * Encodes a gated part as its state, together with its value when shown. The
    * value is always included when shown, even when it is itself `null`, so
    * that a part shown as nothing can never be read as an absent one.
    */
  given encoder[X : Encoder]: Encoder.AsObject[Gated[X]] = Encoder
    .AsObject
    .instance:
      case Shown(value) => JsonObject(
          "state" -> shownState.asJson,
          "value" -> value.asJson,
        )
      case Absent   => JsonObject("state" -> absentState.asJson)
      case Withheld => JsonObject("state" -> withheldState.asJson)

  /** Decodes a gated part from its state, refusing any unknown state. */
  given decoder[X : Decoder]: Decoder[Gated[X]] = Decoder.instance: cursor =>
    cursor
      .downField("state")
      .as[String]
      .flatMap:
        case `shownState`    => cursor.downField("value").as[X].map(Shown(_))
        case `absentState`   => Right(Absent)
        case `withheldState` => Right(Withheld)
        case other           => Left(DecodingFailure(
            s"Unknown gated state `$other`.",
            cursor.history,
          ))
