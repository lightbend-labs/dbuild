package distributed.utils
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.mail.internet.MailDateFormat
import java.util.TimeZone
import java.util.Date
import java.io.File
import org.apache.commons.io.FileUtils.writeStringToFile

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

  // timestamp-related routines

  def timeStampFile(dir: File) = new File(dir, ".timestamp")

  def updateTimeStamp(dir: File) = {
    val dateFormat = new MailDateFormat()
    dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"))
    val date = dateFormat.format(new Date())
    writeStringToFile(timeStampFile(dir), date, "UTF-8")
  }

  /**
   * Age of timestamp in this dir, in hours. For a timestamp
   *  that was last updated 59 minutes ago, it will return zero.
   *  None means no timestamp was found.
   */
  def timeStampAgeHours(dir: File) = {
    val timestamp = timeStampFile(dir)
    if (timestamp.isFile()) {
      val lastModified = timestamp.lastModified()
      if (lastModified == 0) {
        // some IO error occurred. Complain.
        throw new RuntimeException("Unexpected: could not determine age of timestamp file in " + dir)
      }
      val ageMilliseconds = new Date().getTime() - lastModified
      Some(if (ageMilliseconds > 0) ageMilliseconds / 3600000L else 0)
    } else None
  }
}