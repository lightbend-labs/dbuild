package distributed
package support
package mvn

import _root_.distributed.logging.{Logger => DLogger}
import _root_.java.io.File
import org.codehaus.plexus.PlexusContainer
import org.apache.maven.Maven
import org.apache.maven.execution.{
  MavenExecutionRequest,
  DefaultMavenExecutionRequest,
  MavenExecutionRequestPopulator,
  MavenExecutionResult
}
import org.apache.maven.model.Repository
import org.apache.maven.repository.RepositorySystem
import org.apache.maven.artifact.repository.ArtifactRepository
import collection.JavaConverters._

class MavenRunner(container: PlexusContainer) {
  
  
  /** Looks up a class from the nexus container. */
  private def lookup[T](implicit cm: ClassManifest[T]): T =
    (container lookup cm.erasure).asInstanceOf[T]
  
  
  def makeRepo(id: String, url: String, layout: String = "default"): ArtifactRepository = {
    val r = new Repository
    r setId id
    r setUrl url
    r setLayout layout
    val sys = lookup[RepositorySystem]
    sys buildArtifactRepository r
  }
  
  val defaultRepositories = Seq[ArtifactRepository](
     makeRepo("central", "http://repo1.maven.org/maven2/"),
     makeRepo("typesafe", "http://repo.typesafe.com/releases"),
     // TODO - Configure this from build?
     makeRepo("eclipse.indigo", "http://download.eclipse.org/releases/indigo", "p2"),
     makeRepo("eclipse.ajdt.indigo", "http://download.eclipse.org/tools/ajdt/37/dev/update", "p2")
  ).asJava
  
  
  private def makeRequest(pom: File, localRepo: File, log: DLogger) = {
    // TODO - Local repo and community repo should probably be *different* so we don't contaminate.
    val req = new DefaultMavenExecutionRequest
    lookup[MavenExecutionRequestPopulator] populateDefaults req
    
    val lrepo = makeRepo("dbuild", "file://" +localRepo.getAbsolutePath)
    
    req setPom pom
    req setLocalRepositoryPath localRepo
    req setLocalRepository lrepo
    // Add remote repositories.
    req setRemoteRepositories (
        defaultRepositories.asScala
    ).asJava
    // TODO - Set local repo
    req setOffline false
    // TODO - Set proxies
    //req setProxies()
    // TODO - Set *real* properties
    //req setSystemProperties new Properties
    //req setUserProperties new Properties
    req setRecursive true
    req setShowErrors false
    req setLoggingLevel MavenExecutionRequest.LOGGING_LEVEL_INFO
    req setExecutionListener new ExecutionLoggerListener(log)
    req setTransferListener new TransferLoggerListener(log)
    req setCacheNotFound true
    req setCacheTransferError true
    // TODO - This wants us to redirect output for logging... Seriously?
    req
  }
  
  
  def runBuild(pom: File, localRepo: File, log: DLogger): MavenExecutionResult = {
    val request = makeRequest(pom, localRepo, log)
    request setGoals Seq("compile").asJava
    container lookup classOf[Maven] execute request
  }
}