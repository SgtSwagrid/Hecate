package com.alecdorrington.hecate
package model

import io.circe.Codec

/**
  * One registered user.
  *
  * @param id
  *   The unique, stable identifier of this user, assigned by the store.
  *
  * @param username
  *   The unique name this user signs in with. Compared exactly, so `Alice` and
  *   `alice` are two different accounts; a host application that wants one to
  *   be the other normalises before it registers anyone.
  */
final case class User(id: Long, username: String) derives Codec.AsObject

/**
  * The credentials submitted to register an account or sign in.
  *
  * @param username
  *   The name of the account.
  *
  * @param password
  *   The password of the account, in plain text. Only ever sent over the wire;
  *   the server stores a salted hash.
  */
final case class Credentials(username: String, password: String)
  derives Codec.AsObject
