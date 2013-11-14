package distributed.utils
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Contains some time-related utils for general usage.
 */
object Time {
  /** Returns the time it took some command to run */
  def timed[A](f: => A): (A, String) = {
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