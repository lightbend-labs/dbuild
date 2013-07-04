package distributed.support.sbt

import _root_.java.io.File
import _root_.sbt.{IO,Path}
import Path._
import _root_.distributed.logging.Logger
import sys.process._
import distributed.project.model.ExtraConfig
import distributed.project.Defaults

/** A runner for SBT. 
 * TODO - Make it platform synch safe?
 */
class SbtRunner(repos:List[xsbti.Repository], globalBase: File) {
  private val launcherJar = SbtRunner.initSbtGlobalBase(repos,globalBase)
  
  private val defaultProps = 
    Map("sbt.global.base" -> globalBase.getAbsolutePath,
        "sbt.version" -> Defaults.sbtVersion,
        "sbt.override.build.repos" -> "true",
        "sbt.log.noformat" -> "true")
        

  
  def localIvyProps: Map[String,String] =
    Map("sbt.ivy.home" -> (globalBase / ".ivy2").getAbsolutePath)
  
  def run(projectDir: File,
          log: Logger,
          javaProps: Map[String,String] = Map.empty,
          javaArgs: Seq[String] = SbtRunner.defaultJavaArgs,
          extraArgs: Seq[String] = Seq.empty)(args: String*): Unit = {
    removeProjectBuild(projectDir, log)

    val cmd = SbtRunner.makeShell(
        launcherJar.getAbsolutePath,
        defaultProps,
        javaProps,
        javaArgs,
        extraArgs)(args:_*)

    log.debug("Running: " + cmd.mkString("[", ",", "]"))
    Process(cmd, Some(projectDir)) ! log match {
      case 0 => ()
      // TODO - SBT specific failures?
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
   "-Xss2m",
   "-XX:MaxPermSize=512m",
   "-XX:ReservedCodeCacheSize=192m"    
  )
  
  /** inits global base and returns location of launcher jar file. */
  private def initSbtGlobalBase(repos:List[xsbti.Repository], dir: File): File = {
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
    // always rewrite the repo file
    writeRepoFile(repos,repoFile)
    launcherJar
  }
  
  private def makeArgsFromProps(props: Map[String,String]): Seq[String] =
    props.map { case (k,v) => "-D%s=%s" format (k,v) } {collection.breakOut}
    
    
  def makeShell(launcherJar: String,
          defaultProps: Map[String,String],
          javaProps: Map[String,String] = Map.empty,
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
          getOrElse sys.error("Could not find "+r+" on the path."))
     try IO.transfer(in, f)
     finally in.close
  }
  
  def writeDeps(file: File): Unit =
    IO.write(file, """addSbtPlugin("com.typesafe.dbuild" % "distributed-sbt-plugin" % """+'"'+ Defaults.version + "\")")
  
  def writeRepoFile(repos:List[xsbti.Repository], config: File): Unit =
    Repositories.writeRepoFile(repos, config)
}