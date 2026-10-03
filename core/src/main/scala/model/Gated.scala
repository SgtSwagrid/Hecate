package com.alecdorrington.hecate
package model

import io.circe.{Decoder, DecodingFailure, Encoder, JsonObject}
import io.circe.syntax.*

/**
  * One part of a resource as a particular reader may see it: shown, absent
  * because it does not exist, or withheld because the reader may not see it
  * yet. The read-side counterpart of [[Permitted]].
  *
  * Absent and withheld must never be conflated, nor encoded as an empty value.
  * A resource the reader may not see at all is reported as missing, never as
  * one with every part withheld, which would disclose that it exists. Each
  * state is sent under an explicit `state` name, never its case name.
  *
  * @tparam X
  *   The type of the part.
  */
enum Gated[+X]:

  /**
    * A part that exists and that the reader may see.
    *
    * @param value
    *   The part itself.
    */
  case Shown(value: X)

  /** A part that does not exist. */
  case Absent

  /** A part that exists, but that the reader may not see yet. */
  case Withheld

  /**
    * Transforms the part where it is shown.
    *
    * @tparam Y
    *   The type of the transformed part.
    *
    * @param change
    *   The function to apply to a shown part.
    *
    * @return
    *   A part holding the transformed value if shown, and absent or withheld as
    *   before otherwise.
    */
  def map[Y](change: X => Y): Gated[Y] = this match
    case Shown(value) => Shown(change(value))
    case Absent       => Absent
    case Withheld     => Withheld

object Gated:

  /** The `state` a [[Gated.Shown]] part is sent under. Never change it. */
  val shownState: String = "shown"

  /** The `state` a [[Gated.Absent]] part is sent under. Never change it. */
  val absentState: String = "absent"

  /** The `state` a [[Gated.Withheld]] part is sent under. Never change it. */
  val withheldState: String = "withheld"

  /**
    * Gates a piece of text, treating blank text as absent rather than shown.
    *
    * @param text
    *   The text of the part.
    *
    * @return
    *   A part that is absent if the text is blank, and shown otherwise.
    */
  def fromText(text: String): Gated[String] =
    if text.isBlank then Absent else Shown(text)

  /**
    * Includes the value of a shown part even when it encodes as `null`, so that
    * it is never read as absent.
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
