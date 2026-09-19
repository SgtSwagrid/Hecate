package com.alecdorrington.hecate
package model

import io.circe.{Codec, Decoder, Encoder}

/**
  * What a principal may do with a resource, as an ordered scale in which each
  * level includes every level below it: whoever may edit a resource may also
  * view it, and whoever owns it may also edit it.
  *
  * Levels are compared by their position on the scale, but always stored and
  * sent by an explicitly spelled name, never by position or by identifier. So a
  * level may later be inserted between two others, or renamed in the code,
  * without reinterpreting or orphaning anything already stored.
  *
  * @param name
  *   The stable name this level is stored and sent as (e.g. `view`). Must never
  *   change once any level has been stored.
  */
enum Access(val name: String):

  /** May see the resource. */
  case View extends Access("view")

  /** May change the content of the resource. */
  case Edit extends Access("edit")

  /** May delete the resource, and grant access to it. */
  case Own extends Access("own")

  /**
    * Whether this level includes the given level.
    *
    * @param required
    *   The level that is needed.
    *
    * @return
    *   Whether this level is the given level or any level above it.
    */
  def includes(required: Access): Boolean = ordinal >= required.ordinal

object Access:

  /** Orders the levels from least to most permissive. */
  given Ordering[Access] = Ordering.by(_.ordinal)

  /** Encodes each level as its [[Access.name]], refusing unknown names. */
  given Codec[Access] = Codec.from(
    Decoder
      .decodeString
      .emap(name => fromName(name).toRight(s"Unknown access level `$name`.")),
    Encoder.encodeString.contramap(_.name),
  )

  /**
    * Finds a level by the name it is stored and sent as.
    *
    * @param name
    *   The stored name of the level (e.g. `view`).
    *
    * @return
    *   A level with the given name, or `None` if there is no such level.
    */
  def fromName(name: String): Option[Access] = values.find(_.name == name)
