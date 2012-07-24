package distributed.support.sbt

import _root_.java.io.File
import _root_.sbt.{IO,Path}
import Path._
import _root_.distributed.logging.Logger
import sys.process._

/** A runner for SBT. 
 * TODO - Make it platform synch safe?
 */
class SbtRunner(globalBase: File) {
  private val launcherJar = SbtRunner initSbtGlobalBase globalBase
  
  private val defaultProps = 
    Map("sbt.global.base" -> globalBase.getAbsolutePath,
        "sbt.version" -> SbtConfig.sbtVersion,
        "sbt.override.build.repos" -> "true",
        "sbt.log.noformat" -> "true")
        
  private def makeArgsFromProps(props: Map[String,String]): Seq[String] =
    props.map { case (k,v) => "-D%s=%s" format (k,v) } {collection.breakOut}
  
  def localIvyProps: Map[String,String] =
    Map("sbt.ivy.home" -> (globalBase / ".ivy2").getAbsolutePath)
    
  def run(projectDir: File, 
          log: Logger,
          javaProps: Map[String,String] = Map.empty,
          javaArgs: Seq[String] = SbtRunner.defaultJavaArgs)(args: String*): Unit = {
    removeProjectBuild(projectDir, log)
    
    val cmd = (
      Seq("java") ++
      makeArgsFromProps(javaProps ++ defaultProps) ++ 
      javaArgs ++ 
      Seq("-jar", launcherJar.getAbsolutePath) ++
      args
    )
    log.debug("Running: " + cmd.mkString("[", ",", "]"))
    Process(cmd, Some(projectDir)) ! log match {
      case 0 => ()
      // TODO - SBT speicfic failures?
      case n => sys.error("Failure to run sbt!  Error code: " + n)
    }
  }
  
  private def removeProjectBuild(projectDir: File, log: Logger): Unit = {
    // TODO - Just overwrite sbt.version if necessary....
    val buildProps = projectDir / "project" / "build.properties"
    if(buildProps.exists) {
      log.info("Removing " + buildProps.getAbsolutePath)
      IO.delete(buildProps)
    }
  }
    
  
  override def toString = "Sbt(@%s)" format (globalBase.getAbsolutePath)
}

object SbtRunner {
  // TODO - Configure these somewhere?
  val defaultJavaArgs = Seq(
   "-XX:+CMSClassUnloadingEnabled",
   "-Xms1536m",
   "-Xmx1536m", 
   "-XX:MaxPermSize=384m",
   "-XX:ReservedCodeCacheSize=192m"    
  )
  
  /** inits global base and returns location of launcher jar file. */
  private def initSbtGlobalBase(dir: File): File = {
    if(!(dir / "plugins" / "deps.sbt").exists) {
      val pluginDir = dir / "plugins"
      pluginDir.mkdirs
      writeDeps(pluginDir / "deps.sbt")
      //transferResource("sbt/deps.sbt", pluginDir / "deps.sbt")
    }
    val launcherDir = dir / "launcher"
    val launcherJar = launcherDir / "sbt-launch.jar"
    if(!launcherJar.exists) {
      launcherDir.mkdirs
      transferResource("sbt-launch.jar", launcherJar)
    }
    val repoFile = dir / "repositories" 
    if(!repoFile.exists) {
      writeRepoFile(repoFile)
    }
    launcherJar
  }
  
  private def transferResource(r: String, f: File): Unit = {
     val in = (Option(getClass.getClassLoader.getResourceAsStream(r)) 
          getOrElse sys.error("Could not find "+r+" on the path."))
     try IO.transfer(in, f)
     finally in.close
  }
  
  def writeDeps(file: File): Unit =
    IO.write(file, """addSbtPlugin("com.typesafe.dsbt" % "distributed-sbt-plugin" % """+'"'+ Defaults.version + "\")")
  
  def writeRepoFile(config: File): Unit = {
    val ivyPattern = "[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
    val sb = new StringBuilder("[repositories]\n")
    sb append  "  local\n"
    sb append ("  machine-local: file://%s, %s\n" format (new File("/home/jsuereth/.ivy2/local").getAbsolutePath, ivyPattern))
    sb append  "  maven-central\n"
    sb append  "  sonatype-snapshots: https://oss.sonatype.org/content/repositories/snapshots\n"
    sb append  "  java-annoying-cla-shtuff: http://download.java.net/maven/2/\n"
    sb append ("  typesafe-releases: http://typesafe.artifactoryonline.com/typesafe/releases\n")
    sb append ("  typesafe-ivy-releases: http://typesafe.artifactoryonline.com/typesafe/ivy-releases, %s\n" format (ivyPattern))
    sb append ("  dbuild-snapshots: http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots, %s\n" format (ivyPattern))
    sb append ("  sbt-plugin-releases: http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases, %s\n" format (ivyPattern))
    sb append  "  coda: http://repo.codahale.com\n"
    // This is because the git plugin is too prevalent...
    sb append  "  jgit-repo: http://download.eclipse.org/jgit/maven\n"
    // TODO - Typesafe repositories? ... NAH
    IO.write(config, sb.toString)
  } 
}