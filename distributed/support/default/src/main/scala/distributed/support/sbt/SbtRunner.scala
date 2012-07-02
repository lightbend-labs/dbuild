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
        "sbt.log.noformat" -> "true")
        
  private def makeArgsFromProps(props: Map[String,String]): Seq[String] =
    props.map { case (k,v) => "-D%s=%s" format (k,v) } {collection.breakOut}
  
  def run(projectDir: File, 
          log: Logger,
          javaProps: Map[String,String] = Map.empty,
          javaArgs: Seq[String] = SbtRunner.defaultJavaArgs)(args: String*): Unit = {
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
      transferResource("sbt/deps.sbt", pluginDir / "deps.sbt")
    }
    val launcherDir = dir / "launcher"
    val launcherJar = launcherDir / "sbt-launch.jar"
    if(!launcherJar.exists) {
      launcherDir.mkdirs
      transferResource("sbt/sbt-launch.jar", launcherJar)
    }    
    launcherJar
  }
  
  private def transferResource(r: String, f: File): Unit = {
     val in = (Option(getClass.getClassLoader.getResourceAsStream(r)) 
          getOrElse sys.error("Could not find "+r+" on the path."))
     try IO.transfer(in, f)
     finally in.close
  }
}