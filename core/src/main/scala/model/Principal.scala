package com.alecdorrington.hecate
package model

import io.circe.{Codec, Decoder, Encoder, JsonObject}
import io.circe.syntax.*

/**
  * Whoever access may be granted to: either one user, or one group. Access
  * granted to a group reaches every member of that group and of every group
  * nested inside it, but never the members of any group enclosing it.
  *
  * A principal is stored and sent as its [[kind]] and its [[id]], the kind
  * being spelled out explicitly rather than derived from the name of its case,
  * so that renaming a case in the code cannot orphan anything already stored.
  *
  * @param kind
  *   The name this kind of principal is stored and sent as (e.g. `person`).
  *   This is a persisted storage format, shared by every table that stores a
  *   principal, and must never change once any principal has been stored.
  */
enum Principal(val kind: String):

  /**
    * One user.
    *
    * @param id
    *   The identifier of the user.
    */
  case Person(id: Long) extends Principal(Principal.personKind)

  /**
    * One group.
    *
    * @param id
    *   The identifier of the group.
    */
  case Group(id: Long) extends Principal(Principal.groupKind)

  /**
    * The identifier of the user or group, unique only within its kind. Users
    * and groups are numbered independently, so person `7` and group `7` are
    * different principals, and comparing identifiers across kinds is a bug.
    */
  def id: Long

object Principal:

  /** The stored name of the kind of a [[Principal.Person]]. Never change it. */
  val personKind = "person"

  /** The stored name of the kind of a [[Principal.Group]]. Never change it. */
  val groupKind = "group"

  /**
    * Restores a principal from the kind and identifier it is stored as.
    *
    * @param kind
    *   The stored name of the kind of principal (e.g. `person`).
    *
    * @param id
    *   The identifier of the user or group.
    *
    * @return
    *   A principal of the given kind, or `None` if there is no such kind.
    */
  def of(kind: String, id: Long): Option[Principal] = kind match
    case `personKind` => Some(Person(id))
    case `groupKind`  => Some(Group(id))
    case _            => None

  /** Encodes a principal as its kind and identifier, refusing unknown kinds. */
  given Codec.AsObject[Principal] = Codec
    .AsObject
    .from(
      Decoder
        .forProduct2[(String, Long), String, Long]("kind", "id")((_, _))
        .emap((kind, id) =>
          of(kind, id).toRight(s"Unknown kind of principal `$kind`."),
        ),
      Encoder
        .AsObject
        .instance(principal =>
          JsonObject(
            "kind" -> principal.kind.asJson,
            "id"   -> principal.id.asJson,
          ),
        ),
    )
