package com.alecdorrington.hecate
package model

/**
  * Every reason this library refuses a request, as a value rather than a
  * sentence, so that the sentence can be written in the reader's language by
  * the [[com.alecdorrington.hecate.i18n.Wording]] the host application gives.
  * The server raises and reports these; nothing here says how they read.
  */
enum AuthRefusal:

  /** An endpoint that needs a signed-in user was called without one. */
  case SignedOut

  /** A registration asked for a username that exists already. */
  case UsernameTaken

  /**
    * A sign-in failed. One reason for both halves, deliberately: it must not
    * say which was wrong, or it could be used to find valid usernames.
    */
  case IncorrectCredentials

  /** A confirming password, given again for a consequential request, is wrong. */
  case IncorrectPassword

  /**
    * A recovery failed, saying nothing about which part was wrong, for the same
    * reason as [[IncorrectCredentials]].
    */
  case IncorrectRecovery

  /** The host application offers no account deletion. */
  case NoAccountDeletion

  /** A registration asked for a blank username. */
  case EmptyUsername

  /**
    * A password is shorter than the policy allows.
    *
    * @param min
    *   The fewest characters a password may have.
    */
  case PasswordTooShort(min: Int)

  /**
    * An invitation named a user nobody is named.
    *
    * @param username
    *   The username the invitation asked for.
    */
  case NoSuchUser(username: String)

  /**
    * A request named a group that does not exist, or that belongs to somebody
    * else. One reason for both, so that nothing is disclosed about other users'
    * groups.
    */
  case GroupMissing

  /**
    * A request named an invitation that does not exist, or that was sent to
    * somebody else. One reason for both, as for [[GroupMissing]].
    */
  case InvitationMissing

  /** A group was to be nested inside a parent that does not exist. */
  case ParentGroupMissing

  /** A group was to be nested inside itself, or a group inside it. */
  case GroupInsideItself

  /**
    * An account cannot be deleted while it is the only owner of something.
    *
    * @param count
    *   How many things it is the only owner of.
    */
  case SoleOwner(count: Int)

  /**
    * A request failed for a reason that is not the user's to understand, whose
    * detail stays on the server.
    */
  case Failed
