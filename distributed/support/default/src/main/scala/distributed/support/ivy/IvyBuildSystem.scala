package distributed.support.ivy

import distributed.support.BuildSystemCore
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
import distributed.support.ivy.IvyMachinery.PublishIvyInfo
import distributed.project.dependencies.Extractor
import distributed.project.build.LocalBuildRunner

/** Implementation of the Scala  build system. workingDir is the "target" general dbuild dir */
class IvyBuildSystem(repos: List[xsbti.Repository], workingDir: File) extends BuildSystemCore {

  val name = "ivy"
  type ExtraType = IvyExtraConfig

  // this is the general dbuild one (we don't use it here)
  val dbuildIvyHome = (distributed.repo.core.ProjectDirs.dbuildDir / ".ivy2").getAbsolutePath

  def expandExtra(extra: Option[ExtraConfig], systems: Seq[BuildSystem[Extractor, LocalBuildRunner]], defaults: ExtraOptions) = extra match {
    case None => IvyExtraConfig(false, false, true, Seq.empty, None) // pick default values
    case Some(ec: IvyExtraConfig) => ec
    case _ => throw new Exception("Internal error: ivy build config options have the wrong type. Please report")
  }

  def extractDependencies(extractionConfig: ExtractionConfig, baseDir: File, extractor: Extractor, log: Logger): ExtractedBuildMeta = {
    val config = extractionConfig.buildConfig
    val response = IvyMachinery.resolveIvy(config, baseDir, repos, log)
    val report = response.report
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
      // the first element of "loaded", below, is the module that we have just resolved; in principle
      // we should exclude it from the list of dependencies (the artifact doesn't really depend
      // on itself), but be keep it in the list. Why? Because:
      // - If we are working within a single space, a dependency of an artifact on itself
      //   is always ignored, so no problems there.
      // - If we are working across multiple spaces, we may be interested in grabbing an
      //   artifact from one space, and republishing the same artifact to another. So it is
      //   not really circular, but rather we move it across spaces.
      // That is the reason why the first element of loaded, here, is not skipped.
      val loaded = nodes. /*tail.*/ filter(_.isLoaded)
      val deps = loaded.flatMap { _.getAllArtifacts.toSeq }.distinct
      if (deps.nonEmpty) log.info("Dependencies of project " + config.name + ":")
      deps foreach { d => log.info("  " + d) }
      val q = ExtractedBuildMeta(modRevId.getRevision, Seq(Project(fixName(first.getName), first.getOrganisation,
        firstNode.getAllArtifacts.toSeq.map(artifactToProjectRef).distinct,
        loaded.flatMap { _.getAllArtifacts.toSeq.map(artifactToProjectRef) }.distinct)))
      log.debug(q.toString)
      q
    }
  }

  // TODO: the Ivy build system ignores project.buildOptions.crossVersion!! It rather always republishes
  // using the same cross-versioning format of whatever it resolved.
  // Adding support involves renaming the resolved artifacts, which is more or less what the Assemble
  // build system is doing at this time

  def runBuild(project: RepeatableProjectBuild, baseDir: File, input: BuildInput, localBuildRunner: LocalBuildRunner, log: Logger): BuildArtifactsOut = {
    log.debug("BuildInput is: " + input)
    // first, get the dependencies
    val rewrittenDeps = checkDependencies(project, baseDir, input, log)

    // At this point we have in project.config.uri the module revision that we originally asked
    // for in the project.
    //
    // If one of the projects listed in "input" provides the same module, however, we need to
    // change the request in order to grab the corresponding module revision, instead.
    // All the work concerning spaces has already been done before calling runBuild(), therefore
    // we don't have to do anything special at all concerning spaces.
    val module = IvyMachinery.getIvyProjectModuleID(project.config)
    // Do the incoming artifacts provide some artifacts corresponding to this ModuleID?
    val republishArt = input.artifacts.artifacts.find(a =>
      module.getOrganisation == a.info.organization &&
        fixName(module.getName) == a.info.name)
    // if found, generate an updated project config
    val newProjectConfig = republishArt.map { art =>
      val newModRevID = art.info.organization + "#" + art.info.name + art.crossSuffix + ";" + art.version
      log.info("Republishing artifacts. Dependencies were extracted from:")
      log.info("  " + module.getOrganisation + "#" + module.getName + ";" + module.getRevision)
      log.info("These artifacts will be republished:")
      log.info("  " + newModRevID)
      val newURI = "ivy:" + newModRevID
      project.config.copy(uri = newURI)
    } getOrElse project.config

    val version = input.version
    log.info("Will publish as:")
    log.info("  " + module.getOrganisation + "#" + module.getName + ";" + version)


    // this is transitive = false, only used to retrieve the jars that should be republished later
    val response = IvyMachinery.resolveIvy(newProjectConfig, baseDir, repos, log, transitive = false)
    val report = response.report
    import scala.collection.JavaConversions._
    def artifactToArtifactLocation(a: Artifact) = {
      val mr = a.getModuleRevisionId
      val m = mr.getModuleId
      val name = a.getName
      val trimName = fixName(name)
      val cross = if (trimName != name) name.substring(trimName.length) else ""
      val classifier = Option(a.getExtraAttributes.get("classifier").asInstanceOf[String])
      ArtifactLocation(ProjectRef(trimName, m.getOrganisation, a.getExt, classifier), version /*mr.getRevision*/ , cross)
    }

    val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]].toSeq
    val firstNode = nodes(0)
    val publishArts = firstNode.getAllArtifacts.map(artifactToArtifactLocation).distinct

    val ivyArts = (firstNode.getAllArtifacts.toSeq map { _.getModuleRevisionId }).distinct.flatMap { report.getArtifactsReports(_) } map { _.getLocalFile }
    val ivyRepo = baseDir / ".ivy2" / "cache"

    val localRepo = input.outRepo
    IvyMachinery.publishIvy(response, localRepo, rewrittenDeps, version, log)
    val q = BuildArtifactsOut(Seq(BuildSubArtifactsOut("default-ivy-project",
      publishArts,
      localRepo.***.get.filterNot(file => file.isDirectory) map { LocalRepoHelper.makeArtifactSha(_, localRepo) })))
    log.debug(q.toString)
    q
  }

  private def checkDependencies(project: RepeatableProjectBuild, baseDir: File, input: BuildInput, log: Logger): Seq[PublishIvyInfo] = {
    // I can run a check to verify that libraries that are cross-versioned (and therefore Scala-based) 
    // have been made available via BuildArtifactsIn. If not, emit a message and possibly stop.
    import scala.collection.JavaConversions._
    val crossVersion = project.config.getCrossVersion
    val arts = input.artifacts.artifacts
    // I need to get my dependencies again during build; although in theory I could pass
    // this information to here from extraction, in practice I just run Ivy once more
    // to extract it here again, so that additional artifact-related details are available.
    val response = IvyMachinery.resolveIvy(project.config, baseDir, repos, log)
    val report = response.report
    val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]].toSeq
    val firstNode = nodes(0)
    val first = firstNode.getModuleId
    // We skip the first element (the artifacts resolved) and keep only the dependencies
    val deps = nodes.tail.filter(_.isLoaded).map { n =>
      (n.getAllRealCallers.map(_.getModuleRevisionId.getModuleId).contains(first), // flag: is it a direct dependency?
        n.getConfigurations("optional").nonEmpty, // is the dependency optional?
        n.getAllArtifacts.toSeq) // list of artifacts for this module
    }

    // let's check the dependencies, rewriting them as needed
    def currentName = fixName(first.getName)
    def currentOrg = first.getOrganisation
    if (deps.isEmpty)
      log.info("No dependencies in project " + project.config.name)
    else
      log.info("All dependencies for project " + project.config.name + ":")
    deps flatMap {
      case (direct, optional, as) => as flatMap { a =>
        // This is simplified version of the code in DistributedRunner
        def findArt: Option[ArtifactLocation] =
          (for {
            artifact <- arts.view
            if artifact.info.organization == a.getModuleRevisionId.getOrganisation
            if fixName(artifact.info.name) == fixName(a.getName)
            if artifact.info.extension == a.getExt
          } yield artifact).headOption
        def printIvyDependency(someArt: Option[ArtifactLocation]) {
          log.info("  " + a.getModuleRevisionId.getOrganisation + "#" + a.getName + ";" + a.getModuleRevisionId.getRevision +
            (someArt map { art =>
              " --> " + art.info.organization + "#" + art.info.name + art.crossSuffix + ";" + art.version
            } getOrElse "") +
            (if (direct) "" else " (transitive)") + (if (optional) " (optional)" else ""))
        }
        findArt map { art =>
          printIvyDependency(Some(art))
          val da1 = DefaultArtifact.cloneWithAnotherName(a, art.info.name + art.crossSuffix)
          val mrid = ModuleRevisionId.newInstance(art.info.organization, art.info.name + art.crossSuffix,
            a.getModuleRevisionId.getBranch, art.version,
            a.getModuleRevisionId.getExtraAttributes)
          val da2 = DefaultArtifact.cloneWithAnotherMrid(da1, mrid)
          if (direct) Some(PublishIvyInfo(da2, rewritten = true, optional = optional)) else None
        } getOrElse {
          printIvyDependency(None)
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
                if (optional) {
                  log.warn(msg)
                  log.warn("This dependency is marked optional, and may be not actually in use; continuing...")
                  log.warn("If another project does request this optional dependency, an unspecified version")
                  log.warn("will be retrieved from the external repositories. In order to control which version")
                  log.warn("is used, you may want to add this dependency to the dbuild configuration file.")
                } else {
                  log.error(msg)
                  log.error("In order to control which version is used, please add the corresponding project to the build file")
                  log.error("(or use \"build.options:{cross-version:standard}\" to ignore (not recommended)).")
                  sys.error("Required dependency not found")
                }
              case "standard" =>
                log.warn(msg)
                if (optional) {
                  log.warn("The dependency is marked optional, and may be not actually in use. If another project requests")
                  log.warn("this optional dependency, however, it will be retrieved from the external repositories.")
                } else {
                  log.warn("The library (and possibly some of its dependencies) will be retrieved from the external repositories.")
                }
                log.warn("In order to control which version is used, you may want to add this dependency to the dbuild configuration file.")
              case _ => sys.error("Unrecognized option \"" + crossVersion + "\" in cross-version")
            }
          }
          if (direct) Some(PublishIvyInfo(a, rewritten = false, optional = optional)) else None
        }
      }
    }
  }
}
