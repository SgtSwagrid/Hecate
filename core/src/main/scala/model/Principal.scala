package com.alecdorrington.hecate
package model

import io.circe.{Codec, Decoder, Encoder, JsonObject}
import io.circe.syntax.*

/**
  * A user, a group or the system, any of which access may be granted to. Access
  * granted to a group reaches the members of it and of every group nested
  * inside it, never of the groups enclosing it; access granted to the system
  * reaches every user the host names as acting for it. Stored and sent as its
  * [[kind]] and [[id]].
  *
  * @param kind
  *   The name this kind of principal is stored and sent as (e.g. `person`).
  *   Must never change once stored.
  */
enum Principal(val kind: String):

  /**
    * A user.
    *
    * @param id
    *   The identifier of the user.
    */
  case Person(override val id: Long) extends Principal(Principal.personKind)

  /**
    * A group.
    *
    * @param id
    *   The identifier of the group.
    */
  case Group(override val id: Long) extends Principal(Principal.groupKind)

  // Its kind is a literal, as the companion's strings are not yet set when an
  // enum's singleton case is made.
  /**
    * The system itself, which nobody signs in as: the users the host names act
    * for it. It is told apart by its kind, never by a name.
    */
  case System extends Principal("system")

  /**
    * The identifier of the user or group, unique only within its kind: person
    * `7` and group `7` are different principals. The system's is `0`.
    */
  def id: Long = 0

object Principal:

  /** The stored name of the kind of a [[Principal.Person]]. Never change it. */
  val personKind: String = "person"

  /** The stored name of the kind of a [[Principal.Group]]. Never change it. */
  val groupKind: String = "group"

  /** The stored name of the kind of [[Principal.System]]. Never change it. */
  val systemKind: String = System.kind

  /**
    * Restores a principal from the kind and identifier it is stored as.
    *
    * @param kind
    *   The stored name of the kind of principal (e.g. `person`).
    *
    * @param id
    *   The identifier of the user or group, `0` for the system.
    *
    * @return
    *   A principal of the given kind, or `None` if there is no such kind.
    */
  def of(kind: String, id: Long): Option[Principal] = kind match
    case `personKind`            => Some(Person(id))
    case `groupKind`             => Some(Group(id))
    case `systemKind` if id == 0 => Some(System)
    case _                       => None

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
