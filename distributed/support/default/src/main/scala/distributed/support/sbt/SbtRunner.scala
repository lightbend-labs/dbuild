package distributed.support.sbt

import _root_.java.io.File
import _root_.sbt.{ IO, Path }
import Path._
import _root_.distributed.logging.Logger
import sys.process._
import distributed.project.model.ExtraConfig
import distributed.repo.core.Defaults
import org.apache.commons.io.FileUtils.readFileToString

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
   */
  def buildLevels(dir: File): Int = {
    val sub = dir / "project"
    val subSub = sub / "project"
    if (sub.isDirectory && (sub.*("*.sbt").get.nonEmpty || (subSub.isDirectory() && sub.*("*.scala").get.nonEmpty)))
      buildLevels(sub) + 1
    else 1
  }

  // a filename that should come last, when sorting.
  val dbuildSbtDefinitions = "ÿÿÿÿÿÿÿÿÿÿ~~~~dbuild~defs.sbt"
  // we can change this to "~~~~~~~~~~~~~dbuild~defs" if "ÿ" turns out to be problematic (but it shouldn't be)

  /**
   *  creates the .dbuild directories, one per level
   */
  def prepDBuildDirs(dir: File, left: Int): Unit = {
    if (left > 0) {
      (dir / ".dbuild").mkdir()
      prepDBuildDirs(dir / "project", left - 1)
    }
  }

  /**
   * Place each element of "contents" in the subsequent directories
   * dir, dir/project, dir/project/project, and so on.
   */
  def writeSbtFiles(mainDir: File, contents: Seq[String], log: Logger, debug: Boolean) = {
    // this is how to write one file:
    def writeOneSbtFile(dir: File, content: String) = {
      if (debug) {
        log.debug("Adding dbuild .sbt file to: " + dir.getCanonicalPath())
      }
      dir.mkdir()
      val p = new _root_.java.io.PrintWriter(dir / SbtRunner.dbuildSbtDefinitions)
      p.write(content)
      p.close

    }
    contents.foldLeft(mainDir) { (dir, content) =>
      // Let's write each file in the proper place
      writeOneSbtFile(dir, content)
      dir / "project"
    }
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
