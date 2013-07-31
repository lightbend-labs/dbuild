package distributed.support.ivy

import distributed.project.BuildSystem
import distributed.project.model._
import distributed.repo.core.LocalArtifactMissingException
import java.io.File
import sbt.Path._
import sbt.IO
import distributed.logging.Logger
import sys.process._
import distributed.repo.core.LocalRepoHelper
import distributed.project.model.Utils.readValue
import xsbti.Predefined._
import org.apache.ivy
import ivy.Ivy
import ivy.plugins.resolver.{ BasicResolver, ChainResolver, FileSystemResolver, IBiblioResolver, URLResolver }
import ivy.core.settings.IvySettings
import ivy.core.module.descriptor.{ DefaultModuleDescriptor, DefaultDependencyDescriptor, Artifact }
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorWriter
import ivy.core.module.id.{ ModuleId, ModuleRevisionId }
import ivy.core.resolve.{ ResolveEngine, ResolveOptions }
import ivy.core.report.ResolveReport
import org.apache.ivy.core.resolve.IvyNode
import distributed.support.NameFixer.fixName

/** Implementation of the Scala  build system. workingDir is the "target" general dbuild dir */
class IvyBuildSystem(repos: List[xsbti.Repository], workingDir: File) extends BuildSystem {

  val name = "ivy"
  // this is the general dbuild one (we don't use it here)
  val dbuildIvyHome = (distributed.repo.core.ProjectDirs.dbuildDir / ".ivy2").getAbsolutePath

  def extractDependencies(config: ProjectBuildConfig, baseDir: File, log: Logger): ExtractedBuildMeta = {
    val report = IvyMachinery.operateIvy(config, baseDir, repos, log)
    val modRevId = report.getAllArtifactsReports()(0).getArtifact.getModuleRevisionId
    val module = modRevId.getModuleId()
    import scala.collection.JavaConversions._
    def artifactToProjectRef(a: Artifact) = {
      val m = a.getModuleRevisionId.getModuleId
      val tpe = a.getType
      ProjectRef(fixName(m.getName), m.getOrganisation, a.getExt, if (tpe != "jar") Some(tpe) else None)
    }
    val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]].toSeq
    val firstNode = nodes(0)
    val first = firstNode.getModuleId
    val q = ExtractedBuildMeta(modRevId.getRevision, Seq(Project(fixName(first.getName), first.getOrganisation,
      firstNode.getAllArtifacts.toSeq.map(artifactToProjectRef).distinct,
      nodes.drop(1).filter(_.isLoaded).flatMap { _.getAllArtifacts.toSeq.map(artifactToProjectRef) }.distinct)))
    log.debug(q.toString)
    q
  }

  def runBuild(project: RepeatableProjectBuild, baseDir: File, input: BuildInput, log: Logger): BuildArtifactsOut = {
    checkDependencies(project, baseDir, input, log)

    val version = input.version
    val localRepo = input.outRepo
    // operateIvy() will deliver ivy.xml directly in the outRepo, the other artifacts will follow below
    val report = IvyMachinery.operateIvy(project.config, baseDir, repos, log, ivyxmlDir = Some(localRepo), transitive = false)
    import scala.collection.JavaConversions._
    def artifactToArtifactLocation(a: Artifact) = {
      val mr = a.getModuleRevisionId
      val m = mr.getModuleId
      val name = a.getName
      val trimName = fixName(name)
      val cross = if (trimName != name) name.substring(trimName.length) else ""
      val classifier = Option(a.getExtraAttributes.get("classifier").asInstanceOf[String])
      ArtifactLocation(ProjectRef(trimName, m.getOrganisation, a.getExt, classifier), mr.getRevision, cross)
    }

    val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]].toSeq
    val firstNode = nodes(0)
    val publishArts = firstNode.getAllArtifacts.map(artifactToArtifactLocation).distinct
    val ivyArts = (firstNode.getAllArtifacts.toSeq map { _.getModuleRevisionId }).distinct.flatMap { report.getArtifactsReports(_) } map { _.getLocalFile }
    val ivyRepo = baseDir / ".ivy2" / "cache"

    /*
  
    val localArts = try {
      ivyArts map { ivyFile =>
        val relative = IO.relativize(ivyRepo, ivyFile) getOrElse sys.error("Internal error, not in Ivy cache: " + ivyFile.getAbsolutePath)
        val localFile = localRepo / relative
        IO.copyFile(ivyFile, localFile, false)
        localFile
      }
    } catch {
      case e: Exception => throw new LocalArtifactMissingException("Unexpected Internal error while copying Ivy artifact to local repo", e.getMessage)
    }
   
*/
    //localRepo.***.get foreach println
    //println("--")
    //ivyRepo.***.get foreach println
    val q = BuildArtifactsOut(Seq(BuildSubArtifactsOut("",
      publishArts,
      localRepo.***.get.filterNot(file => file.isDirectory) map { LocalRepoHelper.makeArtifactSha(_, localRepo) })))
    log.debug(q.toString)
    q
  }

  private def checkDependencies(project: RepeatableProjectBuild, baseDir: File, input: BuildInput, log: Logger): Unit = {
    // I can run a check to verify that libraries that are cross-versioned (and therefore Scala-based) 
    // have been made available via BuildArtifactsIn. If not, emit a message and possibly stop.
    import scala.collection.JavaConversions._
    import project.buildOptions.crossVersion
    val arts = input.artifacts.artifacts
    // I need to get my dependencies, again, and I'd rather not re-resolve using Ivy again.
    // I should try to get again the extraction info, instead. The only problem is that
    // the extraction info can only be gathered via the extract() in Extractor, which is
    // nested into three or four levels of indirections, via enclosing actors. So that is
    // not really viable, as it's really hard to get to it from here. So we can't cache. TODO: try and fix?
    val report = IvyMachinery.operateIvy(project.config, baseDir, repos, log)
    val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]].toSeq
    val firstNode = nodes(0)
    val first = firstNode.getModuleId
    val deps = nodes.drop(1).filter(_.isLoaded).flatMap { _.getAllArtifacts.toSeq }.distinct

    // let's check.
    def currentName = fixName(first.getName)
    def currentOrg = first.getOrganisation
    log.info("All Dependencies for project " + project.config.name + ":")
    deps foreach { a =>
      // This is simplified version of the code in DistributedRunner
      def findArt: Option[ArtifactLocation] =
        (for {
          artifact <- arts.view
          if artifact.info.organization == a.getModuleRevisionId.getOrganisation
          if fixName(artifact.info.name) == fixName(a.getName)
          if artifact.info.extension == a.getExt
        } yield artifact).headOption
      findArt map { art =>
        log.info("  " + a.getModuleRevisionId.getOrganisation + "#" + a.getName + " --> " +
          art.info.organization + "#" + art.info.name + art.crossSuffix + ";" + art.version)
      } getOrElse {
        if (a.getName != fixName(a.getName) &&
          // Do not inspect the artifacts that we are building right at this time:
          (fixName(a.getName) != currentName || a.getModuleRevisionId.getOrganisation != currentOrg)) {
          // If we are here, it means that this is a library dependency that is required,
          // that refers to an artifact that is not provided by any project in this build,
          // and that needs a certain Scala version (range) in order to work as intended.
          // We check crossVersion: if it requires the correspondence to be exact, we fail;
          // otherwise we just print a warning and leave Ivy to fail if need be.
          val msg = "**** Missing dependency: the library " + a.getModuleRevisionId.getOrganisation + "#" + fixName(a.getName) +
            " is not provided by any project in this configuration file."
          crossVersion match {
            case "binaryFull" | "disabled" | "full" =>
              log.error(msg)
              log.error("Please add the corresponding project to the build file (or use \"build-options:{cross-version:standard}\" to ignore).")
              sys.error("Required dependency not found")
            case "standard" =>
              log.warn(msg)
              log.warn("The library (and possibly some of its dependencies) will be retrieved from the external repositories.")
            case _ => sys.error("Unrecognized option \"" + crossVersion + "\" in cross-version")
          }
        }
        log.info("  " + a.getModuleRevisionId.getOrganisation + "#" + a.getName + ";" + a.getModuleRevisionId.getRevision)
      }
    }
  }
}
