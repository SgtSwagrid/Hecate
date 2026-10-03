package com.alecdorrington.hecate
package model

import io.circe.{Codec, Decoder, Encoder}

/**
  * A level of access to a resource, each level including every level below it.
  * Levels are stored and sent by [[code]], never by position.
  *
  * @param code
  *   The code this level is stored and sent as (e.g. `view`). Must never change
  *   once stored.
  */
enum Access(val code: String):

  /** May see the resource. */
  case View extends Access("view")

  /** May change the resource. */
  case Edit extends Access("edit")

  /** May delete the resource, and grant access to it. */
  case Own extends Access("own")

  /**
    * Checks whether this level includes another.
    *
    * @param required
    *   The level that is needed.
    *
    * @return
    *   Whether this level is the required level or above it.
    */
  def includes(required: Access): Boolean = ordinal >= required.ordinal

object Access:

  /** Orders the levels from least to most permissive. */
  given Ordering[Access] = Ordering.by(_.ordinal)

  given Codec[Access] = Codec.from(
    Decoder
      .decodeString
      .emap(code => fromCode(code).toRight(s"Unknown access level `$code`.")),
    Encoder.encodeString.contramap(_.code),
  )

  /**
    * Finds a level by its [[Access.code]].
    *
    * @param code
    *   The stored code of the level (e.g. `view`).
    *
    * @return
    *   A level with the given code, or `None` if there is none.
    */
  def fromCode(code: String): Option[Access] = values.find(_.code == code)
