package distributed

import org.apache.maven.repository.internal.MavenServiceLocator
import org.apache.maven.repository.internal.MavenRepositorySystemSession
import org.sonatype.aether.{
  RepositorySystem,
  RepositorySystemSession
}
import org.sonatype.aether.connector.wagon.{
  WagonProvider, 
  WagonRepositoryConnectorFactory,
  PlexusWagonProvider
}
import org.sonatype.aether.spi.connector.RepositoryConnectorFactory
import org.sonatype.aether.repository.LocalRepository
import org.sonatype.aether.util.DefaultRepositorySystemSession
import org.sonatype.aether.RepositorySystemSession
import java.io.File

/** Helper methods for dealing with starting up Aether. */
package object repo {
  def newRepositorySystemImpl: RepositorySystem = {
    val locator = new MavenServiceLocator
    locator.addService(classOf[RepositoryConnectorFactory], classOf[WagonRepositoryConnectorFactory])
    locator.getService(classOf[RepositorySystem])
  }
  def newSessionImpl(system: RepositorySystem, localRepoDir: File): RepositorySystemSession  = {
    val session = new MavenRepositorySystemSession();
    val localRepo = new LocalRepository(localRepoDir.getAbsolutePath)
    session setLocalRepositoryManager (system newLocalRepositoryManager localRepo)
    session
  }
  
  
  def effectivePom(localRepo: File, pom: File) = 
    MvnPomResolver(localRepo).loadEffectivePom(pom, Seq.empty)
}