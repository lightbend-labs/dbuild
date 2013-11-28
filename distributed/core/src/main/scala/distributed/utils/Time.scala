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
import org.apache.commons.io.FileUtils.touch
import distributed.project.model.CleanupExpirations

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

  private def withSuffix(dir: File, suffix: String) = {
    val name = dir.getName()
    // We cannot place the control files inside the dir, as the dir content
    // may be cleaned occasionally (via git, for example).
    // Therefore we create the timestamp etc. at the same level
    new File(dir.getCanonicalFile().getParentFile(), "." + name + "-" + suffix)
  }

  private def timeStampFile(dir: File) = withSuffix(dir, "timestamp")
  private def successFile(dir: File) = withSuffix(dir, "success")

  // updateTimeStamp is called at the beginning, so beware about
  // the time it takes to actually build/extract
  // Upon invocation, it deletes the success marker, in case we
  // re-extract in the same dir, but this time we fail while we
  // previously succeeded
  def updateTimeStamp(dir: File) = {
    val dateFormat = new MailDateFormat()
    dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"))
    val date = dateFormat.format(new Date())
    writeStringToFile(timeStampFile(dir), date + "\n", "UTF-8")
    successFile(dir).delete()
  }

  def markSuccess(dir: File) = touch(successFile(dir))

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

  private val deletePrefix = "delete-"
  // Sometimes it may happen that we have a directory that has been
  // renamed to "delete-...", but a new directory with the same name
  // has been created. If this second directory were to be deleted
  // as well, a collision would occur.
  // This method takes care of locating a suitable destination name
  // even in that unlikely case
  private def toDeleted(dir: File) = {
    val name = dir.getName()
    val parent = dir.getParentFile()
    def dest(n: Int) = new File(parent, deletePrefix + name +
      (if (n == 0) "" else "-" + n.toString))
    def attempt(n: Int): File = {
      val d = dest(n)
      if (!d.exists())
        d
      else
        attempt(n + 1)
    }
    attempt(0)
  }

  // expiration timeouts are in hours
  // the ">=" comes from the following case:
  // if expiration is zero, even a newly created dir should be deleted right away 
  def upForDeletion(dir: File, exp: CleanupExpirations) = {
    if (markedForDeletion(dir))
      false
    else {
      val isSuccess = successFile(dir).isFile()
      timeStampAgeHours(dir) match {
        case None => false
        case Some(age) =>
          if (isSuccess)
            age >= exp.success
          else
            age >= exp.failure
      }
    }
  }

  // the routines below are not really time-related, but since we placed in this file
  // the timestamp logic, we keep everything together for now. TODO: move this elsewhere
  def prepareForDeletion(dir: File) = {
    dir.renameTo(toDeleted(dir))
    // We also have to get rid of the timestamp and success file
    timeStampFile(dir).delete()
    successFile(dir).delete()
  }

  def markedForDeletion(dir: File) = dir.getName.startsWith(deletePrefix)
}