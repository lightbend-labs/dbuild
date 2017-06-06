package com.typesafe.dbuild.support.sbt

import _root_.java.io.File
import com.typesafe.dbuild.adapter.{Adapter,Defaults}
import Adapter.{ IO, Path, toFF, Logger => SbtLogger }
import Adapter.syntaxio._
import Path._
import com.typesafe.dbuild.logging.Logger
import sys.process._
import com.typesafe.dbuild.model.ExtraConfig
import org.apache.commons.io.FileUtils.readFileToString
import com.typesafe.dbuild.model.Utils.{ readValue, writeValue }
import org.apache.commons.io.FileUtils.writeStringToFile
import com.typesafe.dbuild.logging.Logger.logFullStackTrace

/**
 * A runner for SBT.
 * TODO - Make it platform synch safe?
 */
class SbtRunner(repos: List[xsbti.Repository], globalBase: File, debug: Boolean) {
  private val launcherJar = SbtRunner.initSbtGlobalBase(globalBase, debug)

  private val defaultProps =
    Map("sbt.global.base" -> globalBase.getAbsolutePath,
      "sbt.override.build.repos" -> "true",
      "sbt.log.noformat" -> "true")

  def localIvyProps: Map[String, String] =
    Map("sbt.ivy.home" -> (globalBase / ".ivy2").getAbsolutePath)

  // "process" defaults to processCommand(), below, but it can be
  // customized in order to process the command line in some special manner
  def run(projectDir: File,
    sbtVersion: String,
    log: Logger,
    javaProps: Map[String, String] = Map.empty,
    extraArgs: Seq[String] = Seq.empty,
    process: Option[(File, Logger, File, Seq[String]) => Unit] = None)(args: String*): Unit = {

    val useSbtVersion = if (sbtVersion != "standard") {
      removeProjectBuild(projectDir, log)
      log.info("Using sbt version: " + sbtVersion)
      sbtVersion
    } else {
      val ver = getProjectBuild(projectDir) getOrElse
        sys.error("This project does not define an sbt version with a build.properties file, or the value cannot be parsed. Please specify an sbt version using the \"sbt-version\" option.")
      log.info("Using the sbt version specified by the project: " + ver)
      ver
    }
    // Verify the sbt version number: the new rewiring mechanism requires
    // sbt >0.13.2 and >0.12.4, as up to those versions existing bugs will prevent
    // onLoad from working correctly.
    val regex = "(\\d+)\\.(\\d+)\\.(\\d+).*".r
    useSbtVersion match {
      case regex(major, minor, rev) =>
        if (major == "0" && (
          minor.toInt < 12 ||
          (minor == "12" && rev.toInt < 5) ||
          (minor == "13" && rev.toInt < 3)))
          sys.error("dbuild 0.9 requires at least sbt 0.12.5 or sbt 0.13.3; see docs for the option \"sbt-version\". Invalid: " + useSbtVersion)
      case _ => sys.error("Cannot parse sbt version number: " + useSbtVersion)
    }

    IO.withTemporaryFile("sbtrunner", "lastExceptionMessage") { lastMsg =>
      val cmd = SbtRunner.makeShell(
        launcherJar.getAbsolutePath,
        defaultProps + ("dbuild.sbt-runner.last-msg" -> lastMsg.getCanonicalPath,
          "sbt.version" -> useSbtVersion),
        javaProps,
        extraArgs)(args: _*)

      log.debug("Running: " + cmd.mkString("[", ",", "]"))
      (process getOrElse processCommand _)(projectDir, log, lastMsg, cmd)
    }
  }

  private def processCommand(projectDir: File, log: Logger, lastMsg: File, cmd: Seq[String]): Unit = {
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

  private def removeProjectBuild(projectDir: File, log: Logger): Unit = {
    val buildProps = projectDir / "project" / "build.properties"
    if (buildProps.exists) {
      log.debug("Removing " + buildProps.getAbsolutePath)
      IO.delete(buildProps)
    }
  }

  // if sbt-version is set to "standard", try to parse an existing
  // "build.properties" file
  private def getProjectBuild(projectDir: File): Option[String] = {
    val buildProps = projectDir / "project" / "build.properties"
    if (buildProps.exists()) {
      val lines = scala.io.Source.fromFile(buildProps).getLines
      val regex = " *sbt.version *= *([^ ]*) *".r
      val sbtVer = (for {
        regex(v) <- lines
      } yield v).toList.headOption
      sbtVer
    } else None
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

  /** inits global base and returns location of launcher jar file. */
  private def initSbtGlobalBase(dir: File, debug: Boolean): File = {
    val launcherDir = dir / "launcher"
    val launcherJar = launcherDir / "sbt-launch.jar"
    if (!launcherJar.exists) {
      launcherDir.mkdirs
      transferResource("sbt-launch.jar", launcherJar)
    }
    launcherJar
  }

  private def makeArgsFromProps(props: Map[String, String]): Seq[String] =
    props.map { case (k, v) => "-D%s=%s" format (k, v) } { collection.breakOut }

  def makeShell(launcherJar: String,
    defaultProps: Map[String, String],
    javaProps: Map[String, String] = Map.empty,
    extraArgs: Seq[String] = Seq.empty)(args: String*): Seq[String] = {
    (
      Seq("java") ++
      makeArgsFromProps(javaProps ++ defaultProps) ++
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

  def writeRepoFile(repos: List[xsbti.Repository], config: File, repositories: (String, String)*): Unit =
    Repositories.writeRepoFile(repos, config, repositories:_*)

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
    if (sub.isDirectory && (sub.*(toFF("*.sbt")).get.nonEmpty || (subSub.isDirectory() && sub.*(toFF("*.scala")).get.nonEmpty)))
      buildLevels(sub) + 1
    else 1
  }

  /**
   * Assorted dbuild/sbt-related filenames
   */
  object SbtFileNames {
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

    /**
     * The name of the internal dir that, in each level, will contain the dbuild sbt plugin files.
     *  This need not be the same ".dbuild" dir name that is used during build to rematerialize
     *  artifacts and to collect the resulting artifacts, but it is convenient to reuse the same directory
     *  (see com.typesafe.dbuild.project.build.FileNames.reloadedArtifactsDirName).
     */
    val dbuildSbtDirName = ".dbuild"

    /** Extraction input data */
    val extractionInputFileName = "extraction-input"

    /**
     * the name of the files used to pass rewire information
     * to the (onLoad-driven) rewiring code for each level
     */
    val rewireInputFileName = "rewire-input-data"
    /**
     * the suffix to the ivy cache for each level
     */
    val ivyCacheName = "ivy2"

    /**
     * name of the file where the complete SbtConfig will be
     * dumped, as input to the final building stage at the main level
     */
    val getArtsInputFileName = "build-input-data"
    /**
     * name of the file where the resulting BuildArtifactsOut
     * will be stored, at the end of building
     */
    val outBuildArtsName = "build-out-arts"
    /**
     * name of a private repositories file created in each project build's .dbuild directory
     */
    val repositoriesFileName = "repositories"
  }

  import SbtFileNames._
  /////////////////////////////////////////////////////
  //
  // Below, utilities used by SbtExtractor and SbtBuilder
  // 

  /**
   *  creates the .dbuild directories, one per level
   */
  def prepDBuildDirs(dir: File, left: Int): Unit = {
    if (left > 0) {
      (dir / dbuildSbtDirName).mkdir()
      prepDBuildDirs(dir / "project", left - 1)
    }
  }

  /** write a string to a file named "fileName" in directory "dir" */
  private def placeOneFile(fileName: String, dir: File, content: String) =
    // will mkdirs if necessary
    writeStringToFile(dir / fileName, content, /* default charset */ null: String)

  /**
   * Place each element of "contents" in the subsequent directories
   * mainDir/subDir, mainDir/project/subDir, mainDir/project/project/subDir, and so on, using the specified fileName
   * If dir is None, place the files in mainDir, mainDir/project, mainDir/project/project, etc.
   * Emit diagnostic messages using emit(), which will receive each directory path as a string
   */
  def placeFiles(mainDir: File, contents: Seq[String], fileName: String, subDir: Option[String], emit: String => Unit) = {
    contents.foldLeft(mainDir) { (dir, content) =>
      val thisDir = subDir.map { dir / _ } getOrElse dir
      emit(thisDir.getCanonicalPath())
      placeOneFile(fileName, thisDir, content)
      dir / "project"
    }
  }

  /**
   * Place each element of "contents" in the subsequent directories
   * dir, dir/project, dir/project/project, and so on.
   */
  def writeSbtFiles(mainDir: File, contents: Seq[String], log: SbtLogger, debug: Boolean) =
    placeFiles(mainDir, contents, dbuildSbtFileName, None, s => if (debug) log.debug("Adding dbuild .sbt file to " + s))

  /**
   * Prepare the input data for extraction or build. The first element gets written
   * in dir/.dbuild, the second in dir/project/.dbuild, the dir/project/project/.dbuild,
   * and so on.
   */
  def placeInputFiles[T](mainDir: File, fileName: String, data: Seq[T], log: SbtLogger, debug: Boolean)(implicit m: Manifest[T]) =
    placeFiles(mainDir, data.map { writeValue(_) }, fileName, Some(dbuildSbtDirName), s => if (debug) log.debug("Placing one input file in " + s))

  def rewireInputFile(dir: File) = dir / dbuildSbtDirName / rewireInputFileName

  /**
   * The location of the (per-level) dir used for the ivy cache
   */
  def sbtIvyCache(dir: File) = {
    val cache = dir / dbuildSbtDirName / ivyCacheName
    cache.mkdirs()
    cache
  }

  /**
   * Collect the output files from the various dirs, and return them as a sequence.
   */
  def collectOutputFiles[T](mainDir: File, fileName: String, levels: Int, log: Logger, debug: Boolean)(implicit m: Manifest[T]): Seq[T] = {
    def scan(left: Int, dir: File): Seq[T] = {
      if (left > 0) {
        val file = dir / dbuildSbtDirName / fileName
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
    """addSbtPlugin("com.typesafe.dbuild" % "plugin" % """ + '"' + Defaults.version + "\")\n\n"

  /** Perform a state transformation using onLoad() */
  def onLoad(activity: String) = {
    "onLoad in Global ~= (previousOnLoad => previousOnLoad andThen (state => { " + activity + " }))\n\n"
  }

  // stuff related to generateArtifacts()
  /** Place input data file needed by generateArtifacts() */
  def placeGenArtsInputFile(projectDir: File, content: GenerateArtifactsInput) =
    placeOneFile(getArtsInputFileName, projectDir / dbuildSbtDirName, writeValue(content))
  /** The file where placeGenArtsInputFile() (which which must be consistent) placed its data. */
  def genArtsInputFile(projectDir: File) = projectDir / dbuildSbtDirName / getArtsInputFileName
  /** The file where the resulting BuildArtifactsOut will be stored, at the end of building */
  def buildArtsFile(projectDir: File) = projectDir / dbuildSbtDirName / outBuildArtsName
}
