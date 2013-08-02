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
import org.apache.ivy.core.module.descriptor.DefaultArtifact

/** Implementation of the Scala  build system. workingDir is the "target" general dbuild dir */
class IvyBuildSystem(repos: List[xsbti.Repository], workingDir: File) extends BuildSystem {

  val name = "ivy"
  // this is the general dbuild one (we don't use it here)
  val dbuildIvyHome = (distributed.repo.core.ProjectDirs.dbuildDir / ".ivy2").getAbsolutePath

  def extractDependencies(config: ProjectBuildConfig, baseDir: File, log: Logger): ExtractedBuildMeta = {
    val report = IvyMachinery.operateIvy(config, baseDir, repos, log)
    val artifactReports = report.getAllArtifactsReports()
    if (artifactReports.isEmpty) {
      log.warn("**** Warning: no artifacts found in project " + config.name)
      val module = config.uri.substring(4)
      val modRevId = ModuleRevisionId.parse(module)
      ExtractedBuildMeta(modRevId.getRevision, Seq.empty, Seq.empty)
    } else {
      val modRevId = artifactReports(0).getArtifact.getModuleRevisionId
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
      val deps = nodes.drop(1).filter(_.isLoaded).flatMap { _.getAllArtifacts.toSeq }.distinct
      if (deps.nonEmpty) log.info("Dependencies of project " + config.name + ":")
      deps foreach { d => log.info("  " + d) }
      val q = ExtractedBuildMeta(modRevId.getRevision, Seq(Project(fixName(first.getName), first.getOrganisation,
        firstNode.getAllArtifacts.toSeq.map(artifactToProjectRef).distinct,
        nodes.drop(1).filter(_.isLoaded).flatMap { _.getAllArtifacts.toSeq.map(artifactToProjectRef) }.distinct)))
      log.debug(q.toString)
      q
    }
  }

  def runBuild(project: RepeatableProjectBuild, baseDir: File, input: BuildInput, log: Logger): BuildArtifactsOut = {
    log.debug("BuildInput is: "+input)
    val rewrittenDeps = checkDependencies(project, baseDir, input, log)
    val version = input.version
    val localRepo = input.outRepo
    // operateIvy() will deliver ivy.xml directly in the outRepo, the other artifacts will follow below
    val report = IvyMachinery.operateIvy(project.config, baseDir, repos, log, transitive = false, ivyxmlDir = Some(localRepo), deps = rewrittenDeps,
        publishVersion = version)
    import scala.collection.JavaConversions._
    def artifactToArtifactLocation(a: Artifact) = {
      val mr = a.getModuleRevisionId
      val m = mr.getModuleId
      val name = a.getName
      val trimName = fixName(name)
      val cross = if (trimName != name) name.substring(trimName.length) else ""
      val classifier = Option(a.getExtraAttributes.get("classifier").asInstanceOf[String])
      ArtifactLocation(ProjectRef(trimName, m.getOrganisation, a.getExt, classifier), version /*mr.getRevision*/, cross)
    }

    val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]].toSeq
    val firstNode = nodes(0)
    val publishArts = firstNode.getAllArtifacts.map(artifactToArtifactLocation).distinct
    val ivyArts = (firstNode.getAllArtifacts.toSeq map { _.getModuleRevisionId }).distinct.flatMap { report.getArtifactsReports(_) } map { _.getLocalFile }
    val ivyRepo = baseDir / ".ivy2" / "cache"

    val q = BuildArtifactsOut(Seq(BuildSubArtifactsOut("",
      publishArts,
      localRepo.***.get.filterNot(file => file.isDirectory) map { LocalRepoHelper.makeArtifactSha(_, localRepo) })))
    log.debug(q.toString)
    q
  }

  // mark with true the artifacts that have been rewritten, and should escape Ivy's resolution/conflict management
  private def checkDependencies(project: RepeatableProjectBuild, baseDir: File, input: BuildInput, log: Logger): Seq[(Artifact, Boolean)] = {
    // I can run a check to verify that libraries that are cross-versioned (and therefore Scala-based) 
    // have been made available via BuildArtifactsIn. If not, emit a message and possibly stop.
    import scala.collection.JavaConversions._
    import project.buildOptions.crossVersion
    val arts = input.artifacts.artifacts
    // I need to get my dependencies again during build; although in theory I could pass
    // this information to here from extraction, in practice I just run Ivy once more
    // to extract it here again, so that additional artifact-related details are available.
    val report = IvyMachinery.operateIvy(project.config, baseDir, repos, log)
    val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]].toSeq
    val firstNode = nodes(0)
    val first = firstNode.getModuleId
    val deps = nodes.drop(1).filter(_.isLoaded).map { n =>
      (n.getAllRealCallers.map(_.getModuleRevisionId.getModuleId).contains(first), // is direct dependency?
        n.getAllArtifacts.toSeq)
    }

    // let's check.
    def currentName = fixName(first.getName)
    def currentOrg = first.getOrganisation
    log.info("All Dependencies for project " + project.config.name + ":")
    deps flatMap {
      case (direct, as) => as flatMap { a =>
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
            art.info.organization + "#" + art.info.name + art.crossSuffix + ";" + art.version +
            (if (direct) "" else " (transitive)"))
          val da1 = DefaultArtifact.cloneWithAnotherName(a, art.info.name + art.crossSuffix)
          val mrid = ModuleRevisionId.newInstance(art.info.organization, art.info.name + art.crossSuffix,
            a.getModuleRevisionId.getBranch, art.version,
            a.getModuleRevisionId.getExtraAttributes)
          val da2 = DefaultArtifact.cloneWithAnotherMrid(da1, mrid)
          if (direct) Some((da2, true)) else None
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
          log.info("  " + a.getModuleRevisionId.getOrganisation + "#" + a.getName + ";" + a.getModuleRevisionId.getRevision +
            (if (direct) "" else " (transitive)"))
          if (direct) Some((a, false)) else None
        }
      }
    }
  }
}
