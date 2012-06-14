package distributed
package support
package sbt

import _root_.sbt.{IO, Path, PathExtra}
import Path._
import _root_.java.io.File

object SbtHelper {
  /** Creates the template SBT project for extraction... */
  def makeGlobalBaseIn(dir: File): Unit = 
    if(!(dir / "plugins" / "deps.sbt").exists) {
      val pluginDir = dir / "plugins"
      pluginDir.mkdirs
      transferResource("sbt/deps.sbt", pluginDir / "deps.sbt")
    }
  
  private def transferResource(r: String, f: File): Unit = {
     val in = (Option(getClass.getClassLoader.getResourceAsStream(r)) 
          getOrElse sys.error("Could not find "+r+" on the path."))
     try IO.transfer(in, f)
     finally in.close
  }
}