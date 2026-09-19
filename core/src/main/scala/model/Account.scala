package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * A request to change the signed-in user's password.
  *
  * @param current
  *   The user's present password, confirming that it is really them asking.
  *
  * @param replacement
  *   The password to change it to.
  */
final case class PasswordChange(current: String, replacement: String)
  derives Codec.AsObject

/**
  * A password, given again to confirm a consequential request from someone
  * already signed in, such as deleting their account.
  *
  * @param password
  *   The signed-in user's password.
  */
final case class PasswordCheck(password: String) derives Codec.AsObject

/**
  * A request to regain an account whose password has been forgotten, using one
  * of its recovery codes.
  *
  * @param username
  *   The name of the account.
  *
  * @param code
  *   One unused recovery code of the account. Spacing, hyphens and letter case
  *   are ignored.
  *
  * @param replacement
  *   The new password for the account.
  */
final case class Recovery
  (
    username: String,
    code: String,
    replacement: String,
  )
  derives Codec.AsObject

/**
  * A fresh set of recovery codes, shown to their owner exactly once. Each
  * regains the account once, if its password is forgotten; generating a new set
  * invalidates every earlier code.
  *
  * @param codes
  *   The codes, in the form they are to be written down.
  */
final case class RecoveryCodes(codes: List[String]) derives Codec.AsObject

/**
  * What a client needs to know about the server's rules for accounts, so that
  * it can explain them before a request is refused rather than after.
  *
  * @param minPasswordLength
  *   The fewest characters a password may have.
  *
  * @param accountDeletion
  *   Whether users may delete their own accounts. A host application enables
  *   this only once it can remove everything of its own that belongs to a user.
  */
final case class AuthRules
  (
    minPasswordLength: Int,
    accountDeletion: Boolean,
  )
  derives Codec.AsObject
