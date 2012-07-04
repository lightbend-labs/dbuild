package distributed
package repo

import org.apache.maven.model._
import org.apache.maven.model.io.xpp3.{MavenXpp3Reader, MavenXpp3Writer}
import project.model.{Project=>DProject, BuildArtifacts, ProjectDep, ArtifactLocation, DistributedBuild}
import collection.JavaConverters._

/** A utility to create pom files. */
object PomHelper {
  def makePoms(build: DistributedBuild, arts: BuildArtifacts): Seq[Model] = {
    val helper = new PomHelper(arts)
    for {
      b <- build.builds
      project <- b.extracted.projects
    } yield helper makePom project
  }
  
  def makePomStrings(build: DistributedBuild, arts: BuildArtifacts): Seq[String] =
    makePoms(build, arts) map serialize
    
  def serialize(pom: Model): String = {
    val out = new java.io.StringWriter
    val writer = new MavenXpp3Writer
    try writer.write(out, pom)
    catch {
      case e => 
        e.printStackTrace
        throw e
    }
    finally out.close()
    out.getBuffer.toString
  }
}
/** A helper to generate pom files. */
class PomHelper private (arts: BuildArtifacts) {
  val depVersions: Map[ProjectDep, String] =
    arts.artifacts.map(a => a.dep -> a.version)(collection.breakOut)
  
  def artLocOf(p: DProject): ArtifactLocation =
    (for {
      artifact <- arts.artifacts
      if artifact.dep.name == p.name
      if artifact.dep.organization == p.organization
      if artifact.dep.classifier.isEmpty
    } yield artifact).head
    
  def versionOf(p: DProject): String =
    artLocOf(p).version
  
  def depOf(p: DProject): ProjectDep =
    artLocOf(p).dep
    
  def makePom(p: DProject): Model = {
    val m = new Model
    m setGroupId p.organization
    m setArtifactId p.name
    m setVersion versionOf(p)
    m setPackaging depOf(p).extension
    m setDependencies makeDependencies(p)
    m
  }
  
  // TODO - Remember scope, optional, etc....
  def makeDependencies(p: DProject): java.util.List[Dependency] =
    (for(dep <- p.dependencies) yield {
       val md = new Dependency
       md setGroupId dep.organization
       md setArtifactId dep.name
       md setVersion depVersions.getOrElse(dep, "integration")
       md setType dep.extension
       dep.classifier foreach (md.setClassifier)
       md
     }).asJava
}