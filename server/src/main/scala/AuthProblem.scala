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
  * Its message is the refusal in English, for logs and for tests that match on
  * one; a client is shown it worded in its own language instead.
  *
  * @param refusal
  *   Why the request was refused.
  */
final case class AuthProblem(refusal: AuthRefusal)
  extends Exception(Wording.english.phrase(refusal))
