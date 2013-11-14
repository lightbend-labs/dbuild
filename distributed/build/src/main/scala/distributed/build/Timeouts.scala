package distributed
package build

import akka.actor.{ Actor, ActorRef, Props, Scheduler }
import akka.pattern.{ ask, pipe }
import akka.util.duration._
import akka.util.Timeout
import akka.util.Duration
import akka.util.NonFatal
import akka.dispatch.{ ExecutionContext, Promise, Future }
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object Timeouts {
  // overall timeout for the entire dbuild to complete;
  // should never be reached, unless something truly
  // unexpected occurs. One of the subsequent other
  // timeouts would rather be encountered beforehand.
  val dbuildTimeout: Timeout = 5.hours + 30.minutes

  // timeout that we allow for the entire extraction phase to complete
  val extractionPhaseTimeout: Timeout = 3.hours + 30.minutes
  // timeout that we allow for each extraction to complete
  // (may include git/svn checkout, and Ivy resolution)
  val extractionTimeout: Timeout = 1.hours + 30.minutes
  // timeout that we allow for each build to complete (only during the build phase);
  // a large value is allowed if extractionPlusBuildTimeout is our limit, instead
  val buildTimeout: Timeout = 99.hours
  // timeout that we allow for the entire extraction plus building phases
  // (leave some time for notifications: it should be a bit less than dbuildTimeout)
  val extractionPlusBuildTimeout: Timeout = 5.hours

  // after() was introduced in Akka 2.1.0, but we have to be compatible also with
  // sbt 0.12 -> Scala 2.9 -> Akka 2.0.5. So we copy here an old implementation of the same,
  // with minor changes.

  /**
   * Returns a [[akka.dispatch.Future]] that will be completed with the success or failure of the provided value
   * after the specified duration.
   */
  def after[T](duration: Duration, using: Scheduler)(value: ⇒ Future[T])(implicit ec: ExecutionContext): Future[T] =
    if (duration.isFinite() && duration.length < 1) value else {
      val p = Promise[T]()
      val c = using.scheduleOnce(duration) {
        p completeWith { try value catch { case NonFatal(t) ⇒ Promise.failed(t) } }
      }
      p onComplete { _ ⇒ c.cancel() }
      p
    }
  assert((extractionTimeout.duration + 5.minutes) < extractionPhaseTimeout.duration,
    "extractionTimeout must be a bit shorter than extractionPhaseTimeout")

  // after extraction some data may be pushed to a remote repo, so allow for some time
  assert((extractionPlusBuildTimeout.duration + 10.minutes) > extractionPhaseTimeout.duration,
    "extractionPlusBuildTimeout must be a bit longer than extractionPhaseTimeout")

  // some time will be required for notifications (and possibly deploy) to complete
  assert((extractionPlusBuildTimeout.duration + 25.minutes) < dbuildTimeout.duration,
    "extractionPlusBuildTimeout must be a bit shorter than dbuildTimeout")

  /** Returns the time it took some command to run */
  def timed[A](f: => A) = {
    val s = System.nanoTime
    val ret = f
    val t = System.nanoTime - s
    // Braindead SimpleDateFormat messes up 'S' format
    val time = new Date(t / 1000000L)
    val tenths = (t / 100000000L) % 10L
    val sdf = new SimpleDateFormat("HH'h' mm'm' ss'.'")
    sdf.setTimeZone(TimeZone.getTimeZone("GMT"))
    (ret, sdf.format(time) + tenths + "s")
  }

}
