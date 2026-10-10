package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.i18n.Wording
import com.alecdorrington.hecate.model.AuthRefusal

/**
  * A failure the user should be told about, such as a request naming a missing
  * group. The services answer it with its refusal, worded in the user's
  * language; any other failure is answered as [[AuthRefusal.Failed]], so no
  * driver or SQL detail reaches the client. Its message is the refusal in
  * English.
  *
  * @param refusal
  *   The reason the request was refused.
  */
final case class AuthProblem(refusal: AuthRefusal)
  extends Exception(Wording.english.phrase(refusal))
