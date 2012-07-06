package distributed
package support
package mvn

import project.model._
import org.apache.maven.model.{
  Model => MavenModel,
  Dependency => MavenDependency
}
import org.apache.maven.model.io.xpp3.{MavenXpp3Reader, MavenXpp3Writer}
import org.apache.maven.model.Dependency
import collection.JavaConverters._
import _root_.java.io.{File, FileReader}

// TODO - Do we need to resolve parent poms and all that madness just
// to read these darn things?
object DependencyExtractor {
  
  def extract(pom: File): ExtractedBuildMeta = 
    extract(readFile(pom))
  
  def extract(model: MavenModel): ExtractedBuildMeta =
    ExtractedBuildMeta(model.getUrl, extractProjects(model))
  
  private def readFile(f: File): MavenModel = {
    val reader = new MavenXpp3Reader
    val in = new FileReader(f)
    try reader read in
    finally in.close()
  }
    
  private def extractProjects(model: MavenModel): Seq[Project] = {
    val thisProj = extractProject(model)
    val modules = model.getModules.asScala flatMap (m => extractModule(model, m))
    thisProj +: modules
  }
  
  private def extractModule(model: MavenModel, moduleName: String): Seq[Project] = {
    val dir = model.getBuild.getDirectory
    val modulePom = new File(dir, moduleName + File.separator +  "pom.xml")
    if(!modulePom.exists) sys.error("Problem finding module: " + moduleName + " from " + model)
    extractProjects(readFile(modulePom))
  }
  
  private def extractProject(model: MavenModel): Project = {
    val name = model.getArtifactId
    val org = model.getGroupId
    val ext = model.getPackaging
    // TODO - Do we need to do more to figure out what artifacts we're having here?
    Project(
      name = name,
      organization = org,
      artifacts = Seq(ProjectRef(name,org,ext)),
      dependencies = getDependencies(model.getDependencies)
    )
  }
  
  private def getDependencies(deps: _root_.java.util.List[MavenDependency]): Seq[ProjectRef] =
    for(dep <- deps.asScala) yield {
      ProjectRef(
        name = dep.getArtifactId,
        organization = dep.getGroupId,
        extension = dep.getType,
        classifier = Option(dep.getClassifier)
      )
    }
}