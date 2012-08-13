package distributed
package support
package mvn

import _root_.distributed.logging.{Logger => DLogger}
import _root_.sbt.Level
import _root_.java.io.File
import _root_.java.util.Properties
import org.codehaus.plexus.classworlds.ClassWorld
import org.codehaus.plexus.DefaultContainerConfiguration
import org.codehaus.plexus.DefaultPlexusContainer
import org.apache.maven.execution.MavenExecutionResult
import org.codehaus.plexus.PlexusContainer

object MvnBuilder {
  val classWorld = new ClassWorld("plexus.core", classOf[ClassWorld].getClassLoader)
  def runBuild(pom: File, localRepo: File, log: DLogger): MavenExecutionResult = {
	val maven = new MavenRunner(makeContainer(log))
    maven.runBuild(pom, localRepo, log)
  } 
  
  def makeContainer(log: DLogger) = {
     val cc = (new DefaultContainerConfiguration()
                .setClassWorld(classWorld)
                .setName("mavenCore"))
     // This may be the magic incantation.
     cc setAutoWiring true
     val container = new DefaultPlexusContainer(cc)
     container setLookupRealm null
     container setLoggerManager new DLoggerManager(log)
     container
  }
}
