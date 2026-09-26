package com.alecdorrington.hecate
package api

import com.alecdorrington.hecate.model.{
  Access, AuthRules, Credentials, Group, GroupDraft, GroupView, Invitation,
  Invite, InviteLink, Joinable, LinkPreview, LinkTarget, Membership,
  PasswordChange, PasswordCheck, Recovery, RecoveryCodes, Resource, User,
}
import sttp.tapir.{FieldName, Schema, SchemaType, Validator}

/**
  * The Tapir schema of every model type an endpoint here carries, derived once
  * and shared by [[AuthApi]] and [[GroupApi]], which would otherwise each
  * derive their own of whatever they have in common.
  *
  * Every string these carry is bounded, so that nothing unbounded is ever
  * stored, hashed or compared: the lengths are [[maxNameLength]] and
  * [[maxPasswordLength]], which the two APIs state as their own.
  *
  * These stay within this package. A host application derives the schemas of
  * its own endpoints as it sees fit, and two of the same type in one scope are
  * ambiguous rather than merely wasteful.
  */
private[api] object Schemas:

  /**
    * The most characters a username or a group's name may have. Long enough for
    * any name a person would choose, and short enough that nothing unbounded is
    * ever stored or compared.
    */
  val maxNameLength = 256

  /**
    * The most characters a password may have. Deriving a hash costs time in
    * proportion to the password's length, unauthenticated and on a pinned
    * thread, so a password no person would type is refused at the edge rather
    * than hashed. Long enough for any passphrase, and for any password a
    * manager would generate.
    */
  val maxPasswordLength = 1024

  /** A schema for text of at most the given length. */
  private def bounded(most: Int): Schema[String] = Schema
    .schemaForString
    .validate(Validator.maxLength(most))

  /**
    * The schema every name is derived against, so that a name arrives bounded
    * whichever model carries it. Stays here: a password is bounded by a length
    * of its own, named where it is derived below.
    */
  private given Schema[String] = bounded(maxNameLength)

  given Schema[User]          = Schema.derived
  given Schema[AuthRules]     = Schema.derived
  given Schema[RecoveryCodes] = Schema.derived
  given Schema[Group]         = Schema.derived
  given Schema[GroupView]     = Schema.derived
  given Schema[GroupDraft]    = Schema.derived
  given Schema[Invitation]    = Schema.derived
  given Schema[Invite]        = Schema.derived
  given Schema[Joinable]      = Schema.derived
  given Schema[Membership]    = Schema.derived

  /** A level of access is sent as its name, e.g. `"view"`. */
  given Schema[Access] = Schema
    .string
    .validate(Validator.enumeration(
      Access.values.toList,
      level => Some(level.name),
    ))

  given Schema[Resource]   = Schema.derived
  given Schema[InviteLink] = Schema.derived

  /**
    * The schema of a link's target as its codec sends it: its kind, and the
    * fields of whichever kind it is, of which only the kind is always there.
    */
  given Schema[LinkTarget] = Schema(
    SchemaType.SProduct(List(SchemaType.SProductField[LinkTarget, String](
      FieldName("kind"),
      Schema
        .string
        .validate(Validator.enumeration(List(
          LinkTarget.groupKind,
          LinkTarget.resourceKind,
        ))),
      target => Some(target.kind),
    ))),
    Some(Schema.SName("LinkTarget")),
  )

  given Schema[LinkPreview] = Schema.derived

  given Schema[Credentials] = Schema
    .derived[Credentials]
    .modify(_.password)(_ => bounded(maxPasswordLength))

  given Schema[PasswordChange] = Schema
    .derived[PasswordChange]
    .modify(_.current)(_ => bounded(maxPasswordLength))
    .modify(_.replacement)(_ => bounded(maxPasswordLength))

  given Schema[PasswordCheck] = Schema
    .derived[PasswordCheck]
    .modify(_.password)(_ => bounded(maxPasswordLength))

  given Schema[Recovery] = Schema
    .derived[Recovery]
    .modify(_.replacement)(_ => bounded(maxPasswordLength))
