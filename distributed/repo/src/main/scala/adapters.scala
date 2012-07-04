package distributed
package repo

import project.model.ArtifactLocation
import java.io.File
import org.sonatype.aether.metadata.Metadata
import org.sonatype.aether.artifact.Artifact
import collection.JavaConverters._
    
object adapters {
  
  /** A wrapper for ArtifactLocation that lets us treat it as an Aether Artifact. */
  case class AsAether(art: ArtifactLocation) extends Artifact {
    def getGroupId = art.dep.organization
    def getArtifactId = art.dep.name
    def getVersion = art.version
    def getBaseVersion = art.version
    def setVersion(version: String) = AsAether(art.copy(version = version))
    def isSnapshot = art.version endsWith "-SNAPSHOT"
    def getClassifier = art.dep.classifier getOrElse ""
    def getExtension = art.dep.extension
    def getFile = art.local
    def setFile(file: File) = AsAether(art.copy(local = file))
    
    // Ignore properties for now....
    def getProperty(key: String, default: String) = default
    def getProperties = Map.empty.asJava
    def setProperties(props: java.util.Map[String,String]) = this
  }
  
  /** Interface class for the toAether method. */
  final class ToAether private[adapters] (a: ArtifactLocation) {
    def toAether: Artifact = AsAether(a)
  }
  
  /** Implicit converter for ArtifactLocation to an aether Artifact. */
  implicit def artifactLocation(a: ArtifactLocation): ToAether = new ToAether(a)
}