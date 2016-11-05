package com.typesafe.dbuild.build

import akka.actor.{ Actor, ActorRef, Props, Scheduler }
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import akka.util.Timeout
import scala.util.control.NonFatal
import scala.concurrent.{ ExecutionContext, Promise, Future }
import ExecutionContext.Implicits.global

object Timeouts {
  // overall timeout for the entire dbuild to complete;
  // should never be reached, unless something truly
  // unexpected occurs. One of the subsequent other
  // timeouts would rather be encountered beforehand.
  val dbuildTimeout: Timeout = 20.hours + 30.minutes

  // timeout that we allow for the entire extraction phase to complete
  val extractionPhaseTimeout: Timeout = 18.hours + 30.minutes
  // timeout that we allow for each extraction to complete
  // (may include git/svn checkout, and Ivy resolution)
  val extractionTimeout: Timeout = 16.hours + 30.minutes
  // timeout that we allow for each build to complete (only during the build phase);
  // a large value is allowed if extractionPlusBuildTimeout is our limit, instead
  val buildTimeout: Timeout = 99.hours
  // timeout that we allow for the entire extraction plus building phases
  // (leave some time for notifications: it should be a bit less than dbuildTimeout)
  val extractionPlusBuildTimeout: Timeout = 20.hours

  // after() was introduced in Akka 2.1.0, but we have to be compatible also with
  // sbt 0.12 -> Scala 2.9 -> Akka 2.0.5. So we copy here an old implementation of the same,
  // with minor changes.

  /**
   * Returns a [[akka.dispatch.Future]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  def after[T](duration: FiniteDuration, using: Scheduler)(value: ⇒ Future[T])(implicit ec: ExecutionContext): Future[T] =
    if (duration.isFinite() && duration.length < 1) value else {
      val p = Promise[T]()
      val c = using.scheduleOnce(duration) {
        p completeWith { try value catch { case NonFatal(t) ⇒ Promise.failed(t).future } }
      }
      p.future onComplete { _ ⇒ c.cancel() }
      p.future
    }
  assert((extractionTimeout.duration + 5.minutes) < extractionPhaseTimeout.duration,
    "extractionTimeout must be a bit shorter than extractionPhaseTimeout")

  // after extraction some data may be pushed to a remote repo, so allow for some time
  assert((extractionPlusBuildTimeout.duration + 10.minutes) > extractionPhaseTimeout.duration,
    "extractionPlusBuildTimeout must be a bit longer than extractionPhaseTimeout")

  // some time will be required for notifications (and possibly deploy) to complete
  assert((extractionPlusBuildTimeout.duration + 25.minutes) < dbuildTimeout.duration,
    "extractionPlusBuildTimeout must be a bit shorter than dbuildTimeout")
}
