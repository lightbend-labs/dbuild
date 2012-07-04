package distributed
package repo

import org.sonatype.aether.installation.InstallRequest
import project.model.BuildArtifacts
import org.sonatype.aether.util.artifact.DefaultArtifact

/** This class abstracts a maven local repository so we can install artifacts into it. */
class Repository {
  val system = newRepositorySystemImpl
  
  def install(artifacts: BuildArtifacts): Unit = {    
    val session = newSessionImpl(system, artifacts.localRepo)
    val request = {
      val ir = new InstallRequest
      import adapters.artifactLocation
      // TODO - Add pom artifacts!
      /*
        org.sonatype.aether.artifact.Artifact pomArtifact =
            new DefaultArtifact( model.getGroupId(), model.getArtifactId(), "pom", model.getVersion() ).setFile( pomFile );*/
      artifacts.artifacts foreach (ir addArtifact _.toAether)
      // TODO - Add metadata?
      ir
    }
    system.install(session, request)
    // TODO - Check Result.
  }
}