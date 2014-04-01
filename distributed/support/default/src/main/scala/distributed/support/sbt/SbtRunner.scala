package distributed.support.sbt

import _root_.java.io.File
import _root_.sbt.{ IO, Path }
import Path._
import _root_.distributed.logging.Logger
import sys.process._
import distributed.project.model.ExtraConfig
import distributed.repo.core.Defaults
import org.apache.commons.io.FileUtils.readFileToString
import distributed.project.model.Utils.{ readValue, writeValue }
import org.apache.commons.io.FileUtils.writeStringToFile
import distributed.logging.Logger.logFullStackTrace

/**
 * A runner for SBT.
 * TODO - Make it platform synch safe?
 */
class SbtRunner(repos: List[xsbti.Repository], globalBase: File, debug: Boolean) {
  private val launcherJar = SbtRunner.initSbtGlobalBase(repos, globalBase, debug)

  private val defaultProps =
    Map("sbt.global.base" -> globalBase.getAbsolutePath,
      "sbt.override.build.repos" -> "true",
      "sbt.log.noformat" -> "true")

  def localIvyProps: Map[String, String] =
    Map("sbt.ivy.home" -> (globalBase / ".ivy2").getAbsolutePath)

  def run(projectDir: File,
    sbtVersion: String,
    log: Logger,
    javaProps: Map[String, String] = Map.empty,
    javaArgs: Seq[String] = SbtRunner.defaultJavaArgs,
    extraArgs: Seq[String] = Seq.empty)(args: String*): Unit = {
    removeProjectBuild(projectDir, log)

    IO.withTemporaryFile("sbtrunner", "lastExceptionMessage") { lastMsg =>
      // TODO: the sbt version in the project description is always set to some version
      // Fetch it here, and use it to run the appropriate SBT
      val cmd = SbtRunner.makeShell(
        launcherJar.getAbsolutePath,
        defaultProps + ("dbuild.sbt-runner.last-msg" -> lastMsg.getCanonicalPath,
          "sbt.version" -> sbtVersion),
        javaProps,
        javaArgs,
        extraArgs)(args: _*)

      log.debug("Running: " + cmd.mkString("[", ",", "]"))
      // I need a sort of back channel to return a diagnostic string from sbt,
      // aside from stdin/stderr which are captured by the logger. I use a
      // temporary file and ask the sbt plugin to fill it in, in case of
      // exception, as last thing before returning. If I return, there is
      // nothing in the file, but the return code is !=0, I revert to a
      // generic message.
      Process(cmd, Some(projectDir)) ! log match {
        case 0 => ()
        case n => { // Always exit this block with a throw or sys.error
          val lastErr = try {
            readFileToString(lastMsg, "UTF-8")
          } catch {
            case _ => sys.error("Failure to run sbt!  Error code: " + n)
          }
          sys.error(lastErr)
        }
      }
    }
  }

  private def removeProjectBuild(projectDir: File, log: Logger): Unit = {
    // TODO - Just overwrite sbt.version if necessary....
    val buildProps = projectDir / "project" / "build.properties"
    if (buildProps.exists) {
      log.debug("Removing " + buildProps.getAbsolutePath)
      IO.delete(buildProps)
    }
  }

  override def toString = "Sbt(@%s)" format (globalBase.getAbsolutePath)
}

object SbtRunner {
  // TODO - Configure these somewhere?
  val defaultJavaArgs = Seq(
    "-XX:+CMSClassUnloadingEnabled",
    "-XX:+DoEscapeAnalysis",
    "-Xms1536m",
    "-Xmx1536m",
    "-Xss2m",
    "-XX:MaxPermSize=640m",
    "-XX:ReservedCodeCacheSize=192m"
  )

  def writeQuietIvyLogging(dir: File, debug: Boolean) = {
    val file = new File(dir, ".dbuild.ivy.quiet.sbt")
    if (debug) {
      file.delete()
    } else {
      val p = new _root_.java.io.PrintWriter(file)
      p.write("ivyLoggingLevel in Global := UpdateLogging.Quiet\n")
      p.close
    }
  }
  def silenceIvy(dir: File, log: Logger, debug: Boolean): Unit = {
    val dirP = dir / "project"
    val dirPP = dirP / "project"
    dirP.mkdir()
    Seq(dir, dirP).foreach(writeQuietIvyLogging(_, debug))
    // only change dirPP it need be, otherwise we incur
    // an extra Ivy update for all projects
    if (dirPP.exists()) writeQuietIvyLogging(dirPP, debug)
  }

  /** inits global base and returns location of launcher jar file. */
  private def initSbtGlobalBase(repos: List[xsbti.Repository], dir: File, debug: Boolean): File = {
    /*
     * Let's transfer the plugin and other settings to
     * each build/extraction local dir. That is necessary
     * to support plugins, and to use the new onLoad-based
     * model of extraction/building.
     * 
    val pluginDir = dir / "plugins"
    pluginDir.mkdirs
    writeQuietIvyLogging(pluginDir, debug)
    if (!(pluginDir / "deps.sbt").exists) {
      writeDeps(pluginDir / "deps.sbt")
      //transferResource("sbt/deps.sbt", pluginDir / "deps.sbt")
    }
    */
    val launcherDir = dir / "launcher"
    val launcherJar = launcherDir / "sbt-launch.jar"
    if (!launcherJar.exists) {
      launcherDir.mkdirs
      transferResource("sbt-launch.jar", launcherJar)
    }
    //
    // TODO!!! Different builds may use different lists of
    // repositories, and this location is SINGLE AND SHARED,
    // which absolutely shouldn't be the case.
    val repoFile = dir / "repositories"
    // always rewrite the repo file
    writeRepoFile(repos, repoFile)
    launcherJar
  }

  private def makeArgsFromProps(props: Map[String, String]): Seq[String] =
    props.map { case (k, v) => "-D%s=%s" format (k, v) } { collection.breakOut }

  def makeShell(launcherJar: String,
    defaultProps: Map[String, String],
    javaProps: Map[String, String] = Map.empty,
    javaArgs: Seq[String] = SbtRunner.defaultJavaArgs,
    extraArgs: Seq[String] = Seq.empty)(args: String*): Seq[String] = {
    (
      Seq("java") ++
      makeArgsFromProps(javaProps ++ defaultProps) ++
      javaArgs ++
      extraArgs ++
      Seq("-jar", launcherJar) ++
      args
    )
  }

  def transferResource(r: String, f: File): Unit = {
    val in = (Option(getClass.getClassLoader.getResourceAsStream(r))
      getOrElse sys.error("Could not find " + r + " on the path."))
    try IO.transfer(in, f)
    finally in.close
  }

  def writeDeps(file: File): Unit =
    IO.write(file, """addSbtPlugin("com.typesafe.dbuild" % "distributed-sbt-plugin" % """ + '"' + Defaults.version + "\")")

  def writeRepoFile(repos: List[xsbti.Repository], config: File): Unit =
    Repositories.writeRepoFile(repos, config)

  /**
   * Given a freshly cleaned up checkout of an sbt project (all unrelated files and dirs must be deleted),
   * find the number of levels of the sbt project that will be 'update'd.
   * For a project without plugins or other .sbt files in project/ (or .scala in project/project), that
   * will be one. For a project with plugins, two. And so on.
   * Minimum value is 1.
   */
  def buildLevels(dir: File): Int = {
    val sub = dir / "project"
    val subSub = sub / "project"
    if (sub.isDirectory && (sub.*("*.sbt").get.nonEmpty || (subSub.isDirectory() && sub.*("*.scala").get.nonEmpty)))
      buildLevels(sub) + 1
    else 1
  }

  /**
   * Assorted dbuild/sbt-related filenames
   */
  object FileNames {
    /**
     *  Name of the ".sbt" file that is added to each level in the sbt build
     *  Alphabetically, this filename should come last, when sorting.
     */
    // we can change this to "~~~~~~~~~~~~~dbuild~defs" if "ÿ" turns out to be problematic (but it shouldn't be)
    val dbuildSbtFileName = "ÿÿÿÿÿÿÿÿÿÿ~~~~dbuild~defs.sbt"

    // Below, the names of the files that are contained in each .dbuild directory, for
    // communication with the dbuild sbt plugin

    /** The file where the extraction result is left */
    val extractionOutputFileName = "extraction-output"

    /** The name of the internal dir that, in each level, will contain the dbuild sbt plugin files */
    val dbuildDirName = ".dbuild"

    /** Extraction input data */
    val extractionInputFileName = "extraction-input"

    /** if the dbuild sbt plugin stops prematurely, save the exception information here */
    val lastErrorMessageFileName = "last-error-message"
  }

  import FileNames._
  /////////////////////////////////////////////////////
  //
  // Below, utilities used by SbtExtractor and SbtBuilder
  // 

  /**
   *  creates the .dbuild directories, one per level
   */
  def prepDBuildDirs(dir: File, left: Int): Unit = {
    if (left > 0) {
      (dir / dbuildDirName).mkdir()
      prepDBuildDirs(dir / "project", left - 1)
    }
  }

  /**
   * Place each element of "contents" in the subsequent directories
   * dir, dir/project, dir/project/project, and so on.
   */
  def writeSbtFiles(mainDir: File, contents: Seq[String], log: Logger, debug: Boolean) = {
    contents.foldLeft(mainDir) { (dir, content) =>
      if (debug) log.debug("Adding dbuild .sbt file to " + dir.getCanonicalPath())
      placeOneFile(dbuildSbtFileName, dir, content)
      dir / "project"
    }
  }

  /**
   * Prepare the input data for extraction or build. The first element gets written
   * in dir/.dbuild, the second in dir/project/.dbuild, the dir/project/project/.dbuild,
   * and so on.
   */
  def placeInputFiles[T](mainDir: File, fileName: String, data: Seq[T], log: Logger, debug: Boolean)(implicit m: Manifest[T]) = {
    data.foldLeft(mainDir) { (dir, content) =>
      val dbuildDir = dir / dbuildDirName
      if (debug) log.debug("Placing one input file in " + (dbuildDir / fileName).getCanonicalPath())
      placeOneFile(fileName, dbuildDir, writeValue(content))
      dir / "project"
    }
  }

  // write a string to a file named "fileName" in directory "dir"
  private def placeOneFile(fileName: String, dir: File, content: String) =
    // will mkdirs if necessary
    writeStringToFile(dir / fileName, content, /* default charset */ null: String)

  /**
   * Collect the output files from the various dirs, and return them as a sequence.
   */
  def collectOutputFiles[T](mainDir: File, fileName: String, levels: Int, log: Logger, debug: Boolean)(implicit m: Manifest[T]): Seq[T] = {
    def scan(left: Int, dir: File): Seq[T] = {
      if (left > 0) {
        val file = dir / dbuildDirName / fileName
        val seq =
          try readValue[T](file) +: scan(left - 1, dir / "project")
          catch {
            case e: Exception =>
              logFullStackTrace(log, e)
              sys.error("Failure to parse collected output file " + file.getCanonicalFile())
          }
        if (debug) log.debug("Collected output from " + file.getCanonicalPath())
        seq
      } else Seq.empty
    }
    scan(levels, mainDir)
  }
  // Now, the bits of content that may end up in said .sbt files.
  // Each one should finish with "/n/n", in order to preserve blank lines in between.

  /**
   * If quiet, then silence Ivy resolution
   */
  def ivyQuiet(debug: Boolean) =
    if (debug) "" else "ivyLoggingLevel in Global := UpdateLogging.Quiet\n\n"

  /**
   * The string needed to load the dbuild plugin
   */
  val addDBuildPlugin =
    """addSbtPlugin("com.typesafe.dbuild" % "distributed-sbt-plugin" % """ + '"' + Defaults.version + "\")\n\n"

  /**
   *  Perform a state transformation using onLoad()
   */
  def onLoad(activity: String) = {
    "onLoad in Global <<= (onLoad in Global) { _ andThen { state => { " + activity + " } }}\n\n"
  }
}
