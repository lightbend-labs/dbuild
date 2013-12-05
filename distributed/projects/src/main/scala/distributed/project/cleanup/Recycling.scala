package distributed.project.cleanup
import java.util.Date
import java.util.TimeZone
import javax.mail.internet.MailDateFormat
import java.util.TimeZone
import java.util.Date
import java.io.File
import org.apache.commons.io.FileUtils.writeStringToFile
import org.apache.commons.io.FileUtils.touch
import distributed.project.model.CleanupExpirations

/**
 * Contains timestamp-related cleanup support routines,
 * used in ExtractorActor and BuildRunnerActor, as well
 * as in LocalBuildRunner and Extractor.
 */
object Recycling {
  private def timeStampFile(dir: File) = withSuffix(dir, "timestamp")
  private def successFile(dir: File) = withSuffix(dir, "success")

  private def withSuffix(dir: File, suffix: String) = {
    val name = dir.getName()
    // We cannot place the control files inside the dir, as the dir content
    // may be cleaned occasionally (via git, for example).
    // Therefore we create the timestamp etc. at the same level
    new File(dir.getCanonicalFile().getParentFile(), "." + name + "-" + suffix)
  }

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

  def prepareForDeletion(dir: File) = {
    // let's recheck, in case we mistakenly forget to check if a given dir is already marked
    if (!markedForDeletion(dir)) {
      dir.renameTo(toDeleted(dir))
      // We also have to get rid of the timestamp and success file
      timeStampFile(dir).delete()
      successFile(dir).delete()
    }
  }

  def markedForDeletion(dir: File) = dir.getName.startsWith(deletePrefix)
}