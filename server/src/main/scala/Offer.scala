package com.alecdorrington.hecate
package server

import com.alecdorrington.hecate.model.User

/**
  * What inviting someone to a group did.
  *
  * @param invitee
  *   The user invited.
  *
  * @param invitation
  *   The identifier of the invitation sent, or `None` where none was: the user
  *   was already invited or a member, or joined at once as the inviter or an
  *   applicant.
  */
final case class Offer(invitee: User, invitation: Option[Long])
