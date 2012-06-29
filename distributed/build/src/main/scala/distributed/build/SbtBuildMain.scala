package distributed
package build

import java.io.File
import distributed.project.model.{DistributedBuildParser,BuildArtifacts,DistributedBuildConfig}

/** An Sbt buiild runner. */
class SbtBuildMain extends xsbti.AppMain {
  def run(configuration: xsbti.AppConfiguration) =
    try {
      runWithArgs(configuration.arguments)
      Exit(0)
    } catch {
      case e: Exception => Exit(1)
    }
  
  def runWithArgs(args: Array[String]): Unit = 
    // TODO - Parse inputs for realz...
    if(args.length == 1) build(DistributedBuildParser parseBuildFile new File(args(0)))
    else System.err.println("Usage: dbuild {build-file}")
    
    
  def build(build: DistributedBuildConfig) = {
      val main = new LocalBuildMain
      try main build build
      finally main.dispose
  }
  case class Exit(val code: Int) extends xsbti.Exit
} 