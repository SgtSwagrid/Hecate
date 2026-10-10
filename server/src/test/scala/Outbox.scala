package com.alecdorrington.hecate
package server

import cats.effect.IO
import cats.effect.std.Queue
import scala.concurrent.duration.*

/** A mailer keeping every mail sent, in order, for a test to read. */
final class Outbox private (queue: Queue[IO, Mail]) extends Mailer:

  override def send(mail: Mail): IO[Unit] = queue.offer(mail)

  /** The next mail, failing if none is sent within five seconds. */
  def next: IO[Mail] = queue.take.timeout(5.seconds)

  /** Every unread mail, once background sends have had a moment. */
  def rest: IO[List[Mail]] = IO.sleep(300.millis) *> queue.tryTakeN(None)

object Outbox:

  def apply(): IO[Outbox] = Queue.unbounded[IO, Mail].map(new Outbox(_))

  def mailing(outbox: Outbox): Mailing = Mailing(
    outbox,
    "Test",
    token => s"https://test.example/reset/$token",
    token => s"https://test.example/confirm/$token",
  )

  def token(mail: Mail): String = link
    .findFirstMatchIn(mail.body)
    .map(_.group(1))
    .getOrElse(throw AssertionError(s"No link in: ${ mail.body }"))

  private val link = """https://test\.example/\w+/([\w-]+)""".r
