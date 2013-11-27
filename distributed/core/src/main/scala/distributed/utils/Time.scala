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
    writeStringToFile(timeStampFile(dir), date, "UTF-8")
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

  // expiration timeouts are in hours
  // the ">=" comes from the following case:
  // if expiration is zero, even a newly created dir should be deleted right away 
  def upForDeletion(dir: File, exp: CleanupExpirations) = {
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
  
  // this routine is not really time-related, but since we placed in this file
  // the timestamp logic, we keep everything together
  // Note that we also have to get rid of the timestamp and success file
  def prepareForDeletion(dir: File) = {
    val name = dir.getName()
    val parent = dir.getParentFile()
    val dest = new File(parent,"deleted-"+name)
    dir.renameTo(dest)
    timeStampFile(dir).delete()
    successFile(dir).delete()
  }
}