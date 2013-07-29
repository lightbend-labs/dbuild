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
      ProjectRef(m.getName, m.getOrganisation, a.getExt, if (tpe != "jar") Some(tpe) else None)
    }
    val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]].toSeq
    val firstNode = nodes(0)
    if (!firstNode.isLoaded) {
      sys.error("Unexpected Ivy error: node not loaded (" + firstNode.getModuleId + ")")
    }
    val first = firstNode.getModuleId
    val q = ExtractedBuildMeta(modRevId.getRevision, Seq(Project(first.getName, first.getOrganisation,
      firstNode.getAllArtifacts.toSeq.map(artifactToProjectRef).distinct,
      nodes.drop(1).filter(_.isLoaded).flatMap { _.getAllArtifacts.toSeq.map(artifactToProjectRef) }.distinct)))
    log.debug(q.toString)
    q
  }

  def runBuild(project: RepeatableProjectBuild, baseDir: File, input: BuildInput, log: Logger): BuildArtifactsOut = {
    val version = input.version
    val report = IvyMachinery.operateIvy(project.config, baseDir, repos, log, transitive = false)
    import scala.collection.JavaConversions._
    def artifactToArtifactLocation(a: Artifact) = {
      val mr = a.getModuleRevisionId
      val m = mr.getModuleId
      ArtifactLocation(ProjectRef(m.getName, m.getOrganisation), mr.getRevision, "")
    }
    val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]].toSeq
    val firstNode = nodes(0)
    if (!firstNode.isLoaded) {
      sys.error("Unexpected Ivy error: node not loaded (" + firstNode.getModuleId + ")")
    }
    val ivyArts = (firstNode.getAllArtifacts.toSeq map { _.getModuleRevisionId }).distinct.flatMap { report.getArtifactsReports(_) } map { _.getLocalFile }
    val ivyRepo = baseDir / ".ivy2" / "cache"
    val localRepo = input.outRepo
    val localArts = ivyArts map { ivyFile =>
      try {
        val relative = IO.relativize(ivyRepo, ivyFile) getOrElse sys.error("Internal error, not in Ivy cache: " + ivyFile.getAbsolutePath)
        val localFile = localRepo / relative
        IO.copyFile(ivyFile, localFile, false)
        localFile
      } catch {
        case e: Exception => throw new LocalArtifactMissingException("Unexpected Internal error while copying Ivy artifact to local repo", e.getMessage)
      }
    }

    val q = BuildArtifactsOut(Seq(BuildSubArtifactsOut("",
      firstNode.getAllArtifacts.map(artifactToArtifactLocation).distinct,
      localArts map { LocalRepoHelper.makeArtifactSha(_, localRepo) })).distinct)
    log.debug(q.toString)
    q
  }
}
