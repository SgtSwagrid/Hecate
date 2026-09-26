package com.alecdorrington.hecate
package model

import io.circe.{Codec, Decoder, DecodingFailure, Encoder, Json, JsonObject}
import io.circe.syntax.*

/**
  * What following an invite link gives: membership of a group, or some access
  * over a resource. A target is stored and sent as its [[kind]] and what it
  * names, never by its case's name.
  *
  * @param kind
  *   The name this kind of target is stored and sent as (e.g. `group`).
  */
enum LinkTarget(val kind: String):

  /**
    * Membership of one group, as though its owner had invited whoever follows
    * the link and they had accepted.
    *
    * @param group
    *   The identifier of the group.
    */
  case Joining(group: Long) extends LinkTarget(LinkTarget.groupKind)

  /**
    * The given access over one resource, granted to whoever follows the link.
    *
    * @param resource
    *   The resource.
    *
    * @param access
    *   The level of access granted. Never [[Access.Own]], which is given only
    *   to someone the owner names.
    */
  case Sharing(resource: Resource, access: Access)
    extends LinkTarget(LinkTarget.resourceKind)

object LinkTarget:

  /** The stored name of the kind of a [[LinkTarget.Joining]]. Never change it. */
  val groupKind = "group"

  /** The stored name of the kind of a [[LinkTarget.Sharing]]. Never change it. */
  val resourceKind = "resource"

  /** Encodes a target as its kind and what it names, refusing unknown kinds. */
  given Codec.AsObject[LinkTarget] = Codec
    .AsObject
    .from(
      Decoder.instance(cursor =>
        cursor
          .get[String]("kind")
          .flatMap:
            case `groupKind`    => cursor.get[Long]("group").map(Joining(_))
            case `resourceKind` =>
              for
                resource <- cursor.get[Resource]("resource")
                access   <- cursor.get[Access]("access")
              yield Sharing(resource, access)
            case other => Left(DecodingFailure(
                s"Unknown kind of link target `$other`.",
                cursor.history,
              )),
      ),
      Encoder
        .AsObject
        .instance(target =>
          JsonObject.fromIterable(
            ("kind" -> target.kind.asJson) +: fields(target),
          ),
        ),
    )

  /** What one target names, beside its kind. */
  private def fields(target: LinkTarget): List[(String, Json)] = target match
    case Joining(group)            => List("group" -> group.asJson)
    case Sharing(resource, access) => List(
        "resource" -> resource.asJson,
        "access"   -> access.asJson,
      )

/**
  * The secret an invite link is known by: five letters or digits, compared
  * without regard to case, and never chosen by anyone. It is the whole of what
  * a link needs, so that a link is short enough to read out or copy by hand. At
  * least one of its characters is a digit, so that a code can never spell a
  * word, and a host can put codes beside paths of its own, such as `/tasks`,
  * without either ever being taken for the other.
  */
object InviteCode:

  /** How many characters every code has. */
  val length = 5

  /** Every character a code may hold, as it is stored. */
  val alphabet: String = ('a' to 'z').mkString + ('0' to '9').mkString

  /**
    * The code the given text names, as it is stored, or `None` when it could
    * not be one. Letters are lowered without reference to any locale, so that
    * the same text names the same code wherever it is read.
    *
    * @param text
    *   The text to read a code from, in any case.
    *
    * @return
    *   A code in lower case, or `None` if the text is not one.
    */
  def parse(text: String): Option[String] = Some(text.map(lower)).filter(valid)

  /** Whether the given text, in lower case, is a code. */
  def valid(code: String): Boolean = code.length == length &&
    code.forall(alphabet.contains) && code.exists(_.isDigit)

  /** One letter in lower case, and anything else as it is. */
  private def lower(char: Char): Char =
    if char >= 'A' && char <= 'Z' then (char + ('a' - 'A')).toChar else char

/**
  * One resource's invite link, as its owners see it.
  *
  * @param code
  *   The secret the link is known by (see [[InviteCode]]).
  *
  * @param access
  *   The level of access following the link grants.
  */
final case class InviteLink(code: String, access: Access) derives Codec.AsObject

/**
  * Where an invite link leads, as whoever opens it sees it before they choose
  * whether to follow it.
  *
  * @param target
  *   What following the link gives.
  *
  * @param name
  *   The name of the group or resource it leads to.
  *
  * @param sender
  *   Who made the link, so that whoever opens it knows who is asking.
  *
  * @param already
  *   Whether the user opening the link has everything it gives already, so that
  *   following it would change nothing.
  */
final case class LinkPreview
  (
    target: LinkTarget,
    name: String,
    sender: User,
    already: Boolean,
  )
  derives Codec.AsObject
