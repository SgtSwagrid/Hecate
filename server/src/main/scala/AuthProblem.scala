package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.AuthRefusal

/**
  * A failure that is the user's to understand, such as a request naming a group
  * that does not exist. The services report these worded for the user in their
  * own language, whereas any other failure is reported generically, so that no
  * driver or SQL detail escapes to the client.
  *
  * @param refusal
  *   Why the request was refused.
  */
final case class AuthProblem
  (refusal: AuthRefusal)
  extends Exception(Wording.english.phrase(refusal)):

  /** The refusal in English, for logs and tests; clients read it worded. */
  def message: String = getMessage
