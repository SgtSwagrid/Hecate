package com.alecdorrington.hecate
package i18n

import com.alecdorrington.hecate.model.AuthRefusal

/**
  * Everything this library says to the user, in one language. The server's
  * refusals and the client's own messages are abstract here, and a host
  * application implements them once per language it speaks, so that the library
  * never chooses a language itself. [[Wording.english]] is the one wording the
  * library carries, used wherever the host gives none.
  *
  * The phrases are members rather than a table, so that adding one here fails
  * every language until it is translated.
  */
trait Wording:

  /** Said when an endpoint needs a signed-in user and there is none. */
  val signedOut: String

  /** Said when a requested username exists already. */
  val usernameTaken: String

  /**
    * Said of a failed sign-in. Must say nothing about which half was wrong, so
    * that it cannot be used to find valid usernames.
    */
  val incorrectCredentials: String

  /** Said when a confirming password is wrong. */
  val incorrectPassword: String

  /**
    * Said of a failed recovery. Must say nothing about which part was wrong,
    * for the same reason as [[incorrectCredentials]].
    */
  val incorrectRecovery: String

  /** Said when the host application offers no account deletion. */
  val noAccountDeletion: String

  /** Said of a registration with a blank username. */
  val emptyUsername: String

  /** Said of a password shorter than the given fewest characters. */
  def passwordTooShort(min: Int): String

  /** Said of a username nobody has, in an invitation. */
  def noSuchUser(username: String): String

  /** Said of a group that does not exist, or is somebody else's. */
  val groupMissing: String

  /** Said of an invitation that does not exist, or is somebody else's. */
  val invitationMissing: String

  /**
    * Said to a group's owner admitting someone who has not asked to join it, or
    * who has since withdrawn their request.
    */
  val requestMissing: String

  /**
    * Said of an invite link that leads nowhere: it never existed, or has been
    * replaced or turned off by the group's owner.
    */
  val linkMissing: String

  /** Said of a parent group that does not exist. */
  val parentGroupMissing: String

  /** Said of a group nested inside itself. */
  val groupInsideItself: String

  /**
    * Said to the only owner of the given number of things who tries to delete
    * their account.
    */
  def soleOwner(count: Int): String

  /**
    * Said of any failure that is not the user's to understand. The server's
    * every service reports the same phrase for these, so that a failure's cause
    * cannot be guessed from its wording.
    */
  val requestFailed: String

  /** Said by the client of a reply it could not read. */
  val unreadableReply: String

  /** Said by the client of a request that never reached the server. */
  val unreachable: String

  /** Said by the client once a password has been changed. */
  val passwordChanged: String

  /** The given refusal, as the user reads it. */
  final def phrase(refusal: AuthRefusal): String = refusal match
    case AuthRefusal.SignedOut             => signedOut
    case AuthRefusal.UsernameTaken         => usernameTaken
    case AuthRefusal.IncorrectCredentials  => incorrectCredentials
    case AuthRefusal.IncorrectPassword     => incorrectPassword
    case AuthRefusal.IncorrectRecovery     => incorrectRecovery
    case AuthRefusal.NoAccountDeletion     => noAccountDeletion
    case AuthRefusal.EmptyUsername         => emptyUsername
    case AuthRefusal.PasswordTooShort(min) => passwordTooShort(min)
    case AuthRefusal.NoSuchUser(username)  => noSuchUser(username)
    case AuthRefusal.GroupMissing          => groupMissing
    case AuthRefusal.InvitationMissing     => invitationMissing
    case AuthRefusal.RequestMissing        => requestMissing
    case AuthRefusal.LinkMissing           => linkMissing
    case AuthRefusal.ParentGroupMissing    => parentGroupMissing
    case AuthRefusal.GroupInsideItself     => groupInsideItself
    case AuthRefusal.SoleOwner(count)      => soleOwner(count)
    case AuthRefusal.Failed                => requestFailed

object Wording:

  /**
    * The library's own English, the wording every message is written in first,
    * and the one used when a host application gives no other.
    */
  val english: Wording = new Wording:

    override val signedOut: String = "You need to sign in to do this."

    override val usernameTaken: String = "That username is already taken."

    override val incorrectCredentials: String =
      "Incorrect username or password."

    override val incorrectPassword: String = "Incorrect password."

    override val incorrectRecovery: String =
      "Incorrect username or recovery code."

    override val noAccountDeletion: String = "Accounts can't be deleted here."

    override val emptyUsername: String = "Username can't be empty."

    override def passwordTooShort(min: Int): String =
      s"Password must be at least $min characters."

    override def noSuchUser(username: String): String =
      s"No user is called \"$username\"."

    override val groupMissing: String = "That group doesn't exist."

    override val invitationMissing: String = "That invitation doesn't exist."

    override val requestMissing: String =
      "That request to join doesn't exist any more."

    override val linkMissing: String =
      "That invite link doesn't work. Ask whoever sent it for a new one."

    override val parentGroupMissing: String = "The parent group doesn't exist."

    override val groupInsideItself: String =
      "A group can't be its own subgroup."

    override def soleOwner(count: Int): String =
      val things = if count == 1 then "1 thing" else s"$count things"
      s"You're the only owner of $things. Give them to someone else, or " +
        "delete them, before deleting your account."

    override val requestFailed: String = "The request couldn't be completed."

    override val unreadableReply: String =
      "The server sent a reply that couldn't be read."

    override val unreachable: String = "The server couldn't be reached."

    override val passwordChanged: String =
      "Your password has been changed, and every other session signed out."
