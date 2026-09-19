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
  *   The unique name this user signs in with.
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
