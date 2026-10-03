package com.alecdorrington.hecate
package server

import java.util.Locale

/**
  * The form usernames are compared in. A username is shown as it was chosen,
  * but two that differ only in letter case name one account.
  */
object Username:

  /**
    * Folds a username's letter case, by way of upper case so that letters with
    * several lower-case forms (`ς` and `σ`, `ß` and `ss`) fold alike.
    *
    * @param username
    *   The username, already trimmed.
    *
    * @return
    *   The username's key, equal for any two names differing only in case.
    */
  def key(username: String): String = username
    .toUpperCase(Locale.ROOT)
    .toLowerCase(Locale.ROOT)
