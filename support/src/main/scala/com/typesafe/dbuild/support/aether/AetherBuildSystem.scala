package com.typesafe.dbuild.support.aether

/**
 * *******************************************************************************
 * Parts of this file are adapted from sample Aether demo code which
 * is copyright (c) 2010, 2013 Sonatype, Inc. The copyright notice of the
 * original sample demo code is:
 *
 * ** Copyright (c) 2010, 2013 Sonatype, Inc.
 * ** All rights reserved. This program and the accompanying materials
 * ** are made available under the terms of the Eclipse Public License v1.0
 * ** which accompanies this distribution, and is available at
 * ** http://www.eclipse.org/legal/epl-v10.html
 *
 * See: http://git.eclipse.org/c/aether/aether-demo.git/tree/aether-demo-snippets/
 * *******************************************************************************
 */

import com.typesafe.dbuild.support.BuildSystemCore
import com.typesafe.dbuild.project.{ BuildSystem, BuildData }
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.repo.core.LocalArtifactMissingException
import java.io.File
import sbt.Path._
import sbt.IO
import com.typesafe.dbuild.logging.Logger
import sys.process._
import com.typesafe.dbuild.repo.core.LocalRepoHelper
import com.typesafe.dbuild.model.Utils.readValue
import xsbti.Predefined._
import collection.JavaConversions._
import collection.JavaConverters._
import _root_.java.io.{ FileReader, FileWriter }
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
import com.typesafe.dbuild.support.NameFixer.fixName
import org.apache.ivy.core.module.descriptor.DefaultArtifact
import com.typesafe.dbuild.support.ivy.IvyMachinery.PublishIvyInfo
import com.typesafe.dbuild.project.dependencies.Extractor
import com.typesafe.dbuild.project.build.LocalBuildRunner
import com.typesafe.dbuild.repo.core.GlobalDirs.dbuildHomeDir
import com.typesafe.dbuild.support.SbtUtil.pluginAttrs
import com.typesafe.dbuild.support.assemble.AssembleBuildSystem

import org.apache.maven.model.{ Model, Dependency }
import org.apache.maven.model.io.xpp3.{ MavenXpp3Reader, MavenXpp3Writer }

import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.{ Artifact => AetherArtifact }
import org.eclipse.aether.artifact.{ DefaultArtifact => AetherDefaultArtifact }
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.deployment.DeployRequest
import org.eclipse.aether.deployment.DeploymentException
import org.eclipse.aether.graph.{ Dependency => AetherDependency }
import org.eclipse.aether.graph.DependencyNode
import org.eclipse.aether.installation.InstallRequest
import org.eclipse.aether.installation.InstallationException
import org.eclipse.aether.repository.Authentication
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator
import org.eclipse.aether.util.repository.AuthenticationBuilder
import org.eclipse.aether.transfer.ArtifactNotFoundException

import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory

import org.eclipse.aether.AbstractRepositoryListener
import org.eclipse.aether.RepositoryEvent
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.graph.DependencyNode
import org.eclipse.aether.resolution.ArtifactDescriptorRequest
import org.eclipse.aether.resolution.ArtifactDescriptorResult

class ConsoleRepositoryListener(log: Logger) extends AbstractRepositoryListener {
  override def artifactDeployed(event: RepositoryEvent): Unit =
    log.debug("Deployed " + event.getArtifact() + " to " + event.getRepository())

  override def artifactDeploying(event: RepositoryEvent): Unit =
    log.debug("Deploying " + event.getArtifact() + " to " + event.getRepository())

  override def artifactDescriptorInvalid(event: RepositoryEvent): Unit =
    log.error("Invalid artifact descriptor for " + event.getArtifact() + ": "
      + event.getException().getMessage())

  override def artifactDescriptorMissing(event: RepositoryEvent): Unit =
    log.debug("Missing artifact descriptor for " + event.getArtifact())

  override def artifactInstalled(event: RepositoryEvent): Unit =
    log.debug("Installed " + event.getArtifact() + " to " + event.getFile())

  override def artifactInstalling(event: RepositoryEvent): Unit =
    log.debug("Installing " + event.getArtifact() + " to " + event.getFile())

  override def artifactResolved(event: RepositoryEvent): Unit =
    diagnoseResolution(event, event.getArtifact, "artifact")

  override def artifactDownloading(event: RepositoryEvent): Unit =
    log.debug("Downloading artifact " + event.getArtifact() + " from " + event.getRepository())

  override def artifactDownloaded(event: RepositoryEvent): Unit =
    log.debug("Downloaded artifact " + event.getArtifact() + " from " + event.getRepository())

  override def artifactResolving(event: RepositoryEvent): Unit =
    log.info("Resolving artifact " + event.getArtifact())

  override def metadataDeployed(event: RepositoryEvent): Unit =
    log.debug("Deployed " + event.getMetadata() + " to " + event.getRepository())

  override def metadataDeploying(event: RepositoryEvent): Unit =
    log.debug("Deploying " + event.getMetadata() + " to " + event.getRepository())

  override def metadataInstalled(event: RepositoryEvent): Unit =
    log.debug("Installed " + event.getMetadata() + " to " + event.getFile())

  override def metadataInstalling(event: RepositoryEvent): Unit =
    log.debug("Installing " + event.getMetadata() + " to " + event.getFile())

  override def metadataInvalid(event: RepositoryEvent): Unit =
    log.debug("Invalid metadata " + event.getMetadata())

  override def metadataResolved(event: RepositoryEvent): Unit =
    diagnoseResolution(event, event.getMetadata, "metadata")

  override def metadataResolving(event: RepositoryEvent): Unit =
    log.info("Resolving metadata " + event.getMetadata() + " from " + event.getRepository())

  private def diagnoseResolution[T](event: RepositoryEvent, stuff: T, kind: String) = {
    val repo = event.getRepository
    if (repo == null)
      log.info("Could not resolve " + kind + "!")
    else
      log.info("Resolved " + kind + " " + stuff + " from " + repo)
  }
}

import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.MetadataNotFoundException
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferResource

/**
 * A simplistic transfer listener that logs uploads/downloads to the console.
 */
class ConsoleTransferListener(log: Logger) extends AbstractTransferListener {
  // we cannot use scala.collection.concurrent.Map (TrieMap), as this code must compile under 2.9 as well
  private val downloads =
    new scala.collection.mutable.HashMap[TransferResource, Long] with scala.collection.mutable.SynchronizedMap[TransferResource, Long]

  override def transferInitiated(event: TransferEvent): Unit = {
    val message = if (event.getRequestType() == TransferEvent.RequestType.PUT) "Uploading" else "Downloading"
    log.debug(message + ": " + event.getResource().getRepositoryUrl() + event.getResource().getResourceName())
  }

  override def transferProgressed(event: TransferEvent): Unit = {
    val resource = event.getResource()
    downloads.put(resource, event.getTransferredBytes())

    downloads.foreach {
      case (total, complete) =>
        log.debug(getStatus(complete, total.getContentLength))
    }
  }

  private def toKB(bytes: Long): Long = (bytes + 1023) / 1024;

  private def getStatus(complete: Long, total: Long): String = {
    if (total >= 1024) {
      toKB(complete) + "/" + toKB(total) + " KB ";
    } else if (total >= 0) {
      complete + "/" + total + " B ";
    } else if (complete >= 1024) {
      toKB(complete) + " KB ";
    } else {
      complete + " B ";
    }
  }

  private def transferCompleted(event: TransferEvent): Unit =
    downloads.remove(event.getResource())

  override def transferCorrupted(event: TransferEvent): Unit =
    log.debug(event.getException.getMessage)

  override def transferFailed(event: TransferEvent): Unit = {
    transferCompleted(event)
    log.debug(event.getException.getMessage)
  }

  override def transferSucceeded(event: TransferEvent): Unit = {
    transferCompleted(event)
    val resource = event.getResource()
    val contentLength = event.getTransferredBytes()
    if (contentLength >= 0) {
      val typ = if (event.getRequestType() == TransferEvent.RequestType.PUT) "Uploaded" else "Downloaded"
      val len = if (contentLength >= 1024) toKB(contentLength) + " KB" else contentLength + " B"

      val duration = System.currentTimeMillis() - resource.getTransferStartTime()
      val throughput = if (duration > 0) {
        val bytes = contentLength - resource.getResumeOffset()
        val format = new java.text.DecimalFormat("0.0", new java.text.DecimalFormatSymbols(java.util.Locale.ENGLISH));
        val kbPerSec = (bytes / 1024.0) / (duration / 1000.0);
        " at " + format.format(kbPerSec) + " KB/sec";
      } else ""

      log.info(typ + ": " + resource.getRepositoryUrl() + resource.getResourceName() + " (" + len
        + throughput + ")");
    }
  }
}

object Booter {

  def newRepositorySystem(): RepositorySystem = {
    /*
    * Aether's components implement org.eclipse.aether.spi.locator.Service to ease manual wiring and using the
    * prepopulated DefaultServiceLocator, we only need to register the repository connector and transporter
    * factories.
    */
    val locator = MavenRepositorySystemUtils.newServiceLocator()
    locator.addService(classOf[RepositoryConnectorFactory], classOf[BasicRepositoryConnectorFactory])
    locator.addService(classOf[TransporterFactory], classOf[FileTransporterFactory])
    locator.addService(classOf[TransporterFactory], classOf[HttpTransporterFactory])
    locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
      override def serviceCreationFailed(`type`: Class[_], impl: Class[_], exception: Throwable): Unit =
        {
          exception.printStackTrace();
        }
    })
    locator.getService(classOf[RepositorySystem])
  }

  def newRepositorySystemSession(system: RepositorySystem, log: Logger): DefaultRepositorySystemSession = {
    val session = MavenRepositorySystemUtils.newSession()
    val localRepo = new LocalRepository("target/local-repo")
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo))
    session.setTransferListener(new ConsoleTransferListener(log))
    session.setRepositoryListener(new ConsoleRepositoryListener(log))
    // uncomment to generate dirty trees
    // session.setDependencyGraphTransformer( null )
    session
  }

  def newRepositories(system: RepositorySystem, session: RepositorySystemSession): Seq[RemoteRepository] =
    Seq(newCentralRepository())

  def newCentralRepository(): RemoteRepository =
    new RemoteRepository.Builder("central", "default", "http://central.maven.org/maven2/").build();

}

/** Implementation of the Aether build system. workingDir is the "target" general dbuild dir */
class AetherBuildSystem(repos: List[xsbti.Repository], workingDir: File) extends BuildSystemCore {

  import Booter._
  val name = "aether"
  type ExtraType = AetherExtraConfig

  def expandExtra(extra: Option[ExtraConfig], systems: Seq[BuildSystem[Extractor, LocalBuildRunner]], defaults: ExtraOptions) = extra match {
    case None => AetherExtraConfig(false, false, true, None) // pick default values
    case Some(ec: AetherExtraConfig) => ec
    case _ => throw new Exception("Internal error: aether build config options have the wrong type. Please report")
  }

  private def toMaven(repo: xsbti.Repository, log: Logger) = repo match {
    case m: xsbti.MavenRepository => Some(new RemoteRepository.Builder(m.id, "default", m.url.toURI.toString).build())
    case i: xsbti.IvyRepository =>
      log.debug("Ivy repository " + i.id + " will be ignored."); None
    case p: xsbti.PredefinedRepository => p.id match {
      // "local" is made to point to the same ivyHome, but nothing is ever published there
      case Local =>
        log.debug("The predefined \"local\" Ivy repository will be ignored."); None
      case MavenLocal => // use the global ~/.m2 as a read-only remote repository
        // We are already pointing to a custom Local Repository, above, and we are not going to touch the
        // global one. It would be something like:
        Some(new RemoteRepository.Builder("Maven2 Local", "default", "file://" + System.getProperty("user.home") + "/.m2/repository/").build())
      case MavenCentral =>
        Some(new RemoteRepository.Builder("Maven Central", "default", "http://repo1.maven.org/maven2/").build())
      case ScalaToolsReleases | SonatypeOSSReleases =>
        Some(new RemoteRepository.Builder("Sonatype Releases Repository", "default", "https://oss.sonatype.org/content/repositories/releases").build())
      case ScalaToolsSnapshots | SonatypeOSSSnapshots =>
        Some(new RemoteRepository.Builder("Sonatype Snapshots Repository", "default", "https://oss.sonatype.org/content/repositories/snapshots").build())
      // IMPORTANT: the Scala "snapshots" are currently published in a rather bizarre manner, using special version numbers and a
      // "-SNAPSHOT" suffix. The historical explanation was that multiple versions from the same hash could be published, and in order
      // to avoid granting the "overwrite" privilege a further "-SNAPSHOT" was appended. The result is that there is now (usually)
      // only one (1) "SNAPSHOT" version in the Maven sense for each Scala "snapshot", and the repository handles them as "-SNAPSHOT",
      // therefore replacing the suffix with a timestamp, even though there is actually nowadays a single published version for a
      // given commit. For the exact pattern used in that particular case, check SnapshotPattern and scalaSnapshots() in IvyMachinery.
      // We currently may be unable to resolve those special versions.
    }
  }

  private def resolveAether(module: ModuleRevisionId, localRepo: File, getJar: Boolean,
    rematerializedRepo: Option[File], log: Logger) = {

    import Booter._

    // we resolve directly to the desired output location
    val localRepository = new LocalRepository(localRepo)
    val repositorySystem = Booter.newRepositorySystem()
    val session: DefaultRepositorySystemSession = Booter.newRepositorySystemSession(repositorySystem, log)
    session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, localRepository))

    val definedMavenRepositories = repos.map(toMaven(_, log)).flatten
    val mavenRepositories = rematerializedRepo match {
      case None => definedMavenRepositories
      case Some(repo) =>
        // the dbuild-provided repository must always be in front
        new RemoteRepository.Builder("dbuild-provided", "default", "file://" + repo.getCanonicalFile).build() +: definedMavenRepositories
    }
    log.debug("Using Maven repositories:")
    mavenRepositories foreach { repo =>
      log.debug(repo.toString)
    }

    /*
 * This is the code to grab recursively all the dependencies (unused, but interesting as a reference)
 *
    // This is where the actual artifact we want is prepared for resolution/downloading
    val dependency = new Dependency(artifact, "runtime") // FIXME : customize the configuration (compile/test/whatever, compile by default

    val collectRequest = new CollectRequest()
    collectRequest.setRoot(dependency)
    repos.map(toMaven).flatten.foreach { collectRequest.addRepository }

    val dependencyRequest = new DependencyRequest()
    dependencyRequest.setCollectRequest(collectRequest)

    val rootNode = repositorySystem.resolveDependencies(session, dependencyRequest).getRoot()

    // The result is made up of:
    // - rootNode
    // - resolvedFiles
    // - resolvedClassPath
    case class AetherResult(root: DependencyNode, resolvedFiles: Seq[java.io.File], resolvedClassPath: String)
    def getResult(root: DependencyNode) = {
      val nlg = new PreorderNodeListGenerator()
      root.accept(nlg)
      AetherResult(root, nlg.getFiles().toSeq, nlg.getClassPath())
    }
    
    val result = getResult(rootNode)
    log.info("Result: "+result)
*/

    def failure() = {
      log.error("The artifact could not be resolved from any of:")
      mavenRepositories foreach { r => log.error(r.toString) }
      sys.error("The artifact could not be resolved")
    }

    // It can be more complicated than this, see:
    // http://sonatype.github.io/sonatype-aether/apidocs/org/sonatype/aether/util/artifact/DefaultArtifact.html
    def getArtifact(kind: String) =
      new AetherDefaultArtifact(module.getOrganisation, module.getName, "", kind, module.getRevision)

    val pomArt = getArtifact("pom")
    val jarArt = getArtifact("jar")

    val descriptorRequest = new ArtifactDescriptorRequest(jarArt, mavenRepositories, null)
    val descriptorResult = repositorySystem.readArtifactDescriptor(session, descriptorRequest)
    val pomOrigin = descriptorResult.getRepository // will be null if it didn't resolve
    if (pomOrigin == null) failure()
    // we can also use the resolved pom to grab very easily the direct dependencies:
    // descriptorResult.getDependencies foreach { log.debug }

    val arts = if (getJar) {
      def grab(inArt: AetherArtifact): AetherArtifact = {
        val request = new ArtifactRequest(inArt, mavenRepositories, null)
        val outArtifact = repositorySystem.resolveArtifact(session, request).getArtifact
        val file = outArtifact.getFile
        if (file == null) failure()
        log.debug("The resolved " + inArt.getExtension + " is at: " + file.getCanonicalFile)
        outArtifact
      }
      // If we are downloading artifacts, we would also like to grab the pom again as an artifact,
      // so that we can determine its file location
      val outPomArt = grab(pomArt)
      val outJarArt = grab(jarArt)
      // and of course we can resolve source, javadoc, etc. if needed
      Seq(outPomArt, outJarArt)
    } else Seq(pomArt, jarArt)

    (descriptorResult, arts)
  }
  def extractDependencies(extractionConfig: ExtractionConfig, baseDir: File, extractor: Extractor, log: Logger, debug: Boolean): ExtractedBuildMeta = {
    val config = extractionConfig.buildConfig
    val module = config.uri.substring(7)
    val modRevId = ModuleRevisionId.parse(module)

    // we grab the pom only, directly in the extraction dir
    val (descriptorResult, arts) = resolveAether(modRevId, baseDir, getJar = false, None, log)
    // only the direct dependencies
    val dependencies = descriptorResult.getDependencies.toSeq
    if (dependencies.isEmpty)
      log.debug("There are no direct dependencies")
    else {
      log.debug("The direct dependencies are:")
      dependencies foreach { d => log.debug(d.toString) }
    }

    def artToProjectRef(a: AetherArtifact) = {
      ProjectRef(fixName(a.getArtifactId), a.getGroupId, a.getExtension, if (a.getClassifier != "jar") Some(a.getClassifier) else None)
    }

    ExtractedBuildMeta(modRevId.getRevision, Seq.empty, Seq.empty)
    // (version: String, projects: Seq[Project], subproj: Seq[String] = Seq.empty)
    val q = ExtractedBuildMeta(modRevId.getRevision, Seq(Project(fixName(modRevId.getName()), modRevId.getOrganisation(),
      //  artifacts: Seq[ProjectRef],
      arts map artToProjectRef,
      //  dependencies: Seq[ProjectRef])
      dependencies map { d => artToProjectRef(d.getArtifact) })))
    log.debug(q.toString)
    q
  }

  /*
@Override public ModelSource resolveModel(String groupId,String artifactId,String version) throws UnresolvableModelException {
  Artifact pomArtifact=new DefaultArtifact(groupId,artifactId,"","pom",version);
  try {
    ArtifactRequest request=new ArtifactRequest(pomArtifact,repositories,null);
    pomArtifact=system.resolveArtifact(session,request).getArtifact();
  }
 catch (  ArtifactResolutionException ex) {
    throw new UnresolvableModelException(ex.getMessage(),groupId,artifactId,version,ex);
  }
  File pomFile=pomArtifact.getFile();
  return new FileModelSource(pomFile);
}
   */

  // TODO: the Aether build system ignores project.buildOptions.crossVersion!! It rather always republishes
  // using the same cross-versioning format of whatever it resolved.
  // Adding support involves renaming the resolved artifacts, which is more or less what the Assemble
  // build system is doing at this time

  def runBuild(project: RepeatableProjectBuild, baseDir: File, input: BuildInput, localBuildRunner: LocalBuildRunner,
    buildData: BuildData): BuildArtifactsOut = {
    val log = buildData.log
    log.debug("BuildInput is: " + input)

    // TODO: no knowledge or ability to deal with rewritten dependency is implemented at this time.
    // Right now, we always pretend there are no dependencies, which is sub-optimal but sufficient for
    // 
    val modRevId = AetherBuildSystem.getProjectModuleID(project.config)

    val version = input.version

    // TODO: implement the rename and pom rewriting. That may be absolutely necessary, as we need a different
    // version number when bootstrapping the Scala compiler.
    //    if (version != modRevId.getRevision()) {
    //      sys.error("Unsupported: we asked the mini-Maven build system to republish " + modRevId.getOrganisation + "#" +
    //        modRevId.getName + ";" + modRevId.getRevision +
    //        " as version " + version + ", but the version change code has not been implemented yet.")
    //    }
    // this is transitive = false, only used to retrieve the jars that should be republished later
    //    val response = IvyMachinery.resolveIvy(newProjectConfig, baseDir, repos, log, transitive = false)
    //    def artifactToArtifactLocation(a: Artifact) = {
    //      val mr = a.getModuleRevisionId
    //      val m = mr.getModuleId
    //      val name = a.getName
    //      val trimName = fixName(name)
    //      val cross = if (trimName != name) name.substring(trimName.length) else ""
    //      val classifier = Option(a.getExtraAttributes.get("classifier").asInstanceOf[String])
    //      ArtifactLocation(ProjectRef(trimName, m.getOrganisation, a.getExt, classifier), version /*mr.getRevision*/ , cross, pluginAttrs(mr))
    //    }

    //    val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]].toSeq
    //    val firstNode = nodes(0)
    //    val publishArts = firstNode.getAllArtifacts.map(artifactToArtifactLocation).distinct
    //
    //    val ivyArts = (firstNode.getAllArtifacts.toSeq map { _.getModuleRevisionId }).distinct.flatMap { report.getArtifactsReports(_) } map { _.getLocalFile }
    //    val ivyRepo = baseDir / ".ivy2" / "cache"

    // Information on rematerialized artifacts (ground level only)
    val availableArts = input.artifacts.artifacts
    val availableRepo = input.artifacts.localRepo

    val localRepo = input.outRepo

    // TODO: rather than blindly resolving with the current version number, we should
    // look into the availableArts if the same artifact has already been provided as a
    // dependency by some other dbuild project. If so, we should change groupId (to adapt crossSuffix) and
    // version number in order to make the current request match the available artifact.
    // THEN we can resolve, which will grab the artifact from the availableRepo.
    // We do not need to adjust the dependencies in the pom prior to resolution, since we do not grab the
    // transitive dependencies, and we patch the pom after the fact, below.

    // Right now, no rewiring on the original version/groupId/crossSuffix. FIXME

    val republishArt = availableArts.find(a =>
      modRevId.getOrganisation == a.info.organization &&
        fixName(modRevId.getName) == a.info.name)

    log.info("Requested:")
    log.info("  " + modRevId.getOrganisation + "#" + modRevId.getName + ";" + modRevId.getRevision)
    republishArt match {
      case None => log.info("Not provided by dbuild: will be fetched from an external repository.")
      case Some(art) =>
        log.info("Will use this artifact, provided by dbuild:")
        log.info("  " + art.info.organization + art.crossSuffix + "#" + art.info.name + ";" + art.version)
    }
    log.info("Will republish as:")
    // FIXME : add cross suffix as requested to this org, below
    log.info("  " + modRevId.getOrganisation + "#" + modRevId.getName + ";" + version)

    val (descriptorResult, arts @ Seq(pom, jar)) = resolveAether(modRevId, localRepo, getJar = true, Some(availableRepo), log)
    // DELETE from the resolved local repository all files called "_remote.repositories", which are aether temporary leftovers
    localRepo.**(new sbt.ExactFilter("_remote.repositories")).get.foreach { IO.delete }

    // TODO: add support for source/javadoc/etc jars, as well as plugins.

    // We now have the pom and the jar; we need to adapt the version number and republish, thereby adapting as well
    // the paths, the pom, and finally the sha/md5 checksums.
    // First of all, we need move the files to their new place

    // ??? Recycle the code from Assemble

    // Now we can adapt the pom, rewriting the dependencies 
    AssembleBuildSystem.patchPomDependencies(pom.getFile, availableArts)

    // FIXME: can I support plugins?
    val modulePluginInfo = pluginAttrs(modRevId)
    // TODO: use sbt's internals to transform a IVY plugin reference into a maven one (with double suffix), if applicable

    // at this point the downloaded pom is also in the localRepo. We can just save all of the resolved files,
    // and we're good.

    // Time to calculate the new version, and move about the files in order to keep the repository structure coherent
    // We could be dealing with a Scala artifact (cross versioned or not), a plugin, a Java artifact, or with Scala core jars.
    // We can ignore plugins for now.

    // Now, how do we cope with this mess?...

    //    val scalaVersion = {
    //      val allArts = preCrossArtifactsMap.map(_._2).flatMap(_.results).flatMap(_.artifacts)
    //      allArts.find(l => l.info.organization == "org.scala-lang" && l.info.name == "scala-library").map(_.version)
    //    }
    //    
    //    def getScalaVersion(crossLevel: String) = scalaVersion getOrElse
    //      sys.error("In Assemble, the requested cross-version level is " + crossLevel + ", but no scala-library was found among the artifacts.")
    //        val crossSuff = project.config.getCrossVersionHead match {
    //      case "disabled" => ""
    //      case l @ "full" => "_" + getScalaVersion(l)
    //      case l @ "binary" => "_" + binary(getScalaVersion(l))
    //      case l @ "standard" =>
    //        val version = getScalaVersion(l)
    //        "_" + (if (version.contains('-')) version else binary(version))
    //      case cv => sys.error("Fatal: unrecognized cross-version option \"" + cv + "\"")
    //    }
    //    def patchName(s: String) = fixName(s) + crossSuff

    // Excellent. Now we have to transform the resolved artifact and the pom, in order to convert it to the requested
    // cross suffix and version. We reuse some of the logic from the "Assemble" build system (TODO: consolidate the
    // rewriting in Assemble, Ivy, and Aether).

    sys.error("Enough.")

    val q = BuildArtifactsOut(Seq.empty)
    //    (BuildSubArtifactsOut("default-ivy-project",
    //      publishArts,
    //      localRepo.***.get.filterNot(file => file.isDirectory) map { LocalRepoHelper.makeArtifactSha(_, localRepo) },
    //      com.typesafe.dbuild.manifest.ModuleInfo(organization = module.getOrganisation,
    //        name = fixName(module.getName), version = version, {
    //          import com.typesafe.dbuild.manifest.ModuleAttributes
    //          // We need to calculate CrossBuildProperties; that is made a bit complicated by the fact that
    //          // we specify the ivy module using the full crossversioned name. We need to reconstruct the rest
    //          // from the information available.
    //          if (modulePluginInfo.nonEmpty) { // it's a plugin
    //            ModuleAttributes(modulePluginInfo map { _.scalaVersion }, modulePluginInfo map { _.sbtVersion })
    //          } else {
    //            // the cross suffix can be obtained by diffing name and fixname(name)
    //            val crossSuff = module.getName.drop(fixName(module.getName).length)
    //            val someScala = crossSuff match {
    //              case "" => None
    //              case s if s.startsWith("_") => Some(s.drop(1))
    //              case s => sys.error("Internal Error: crossSuff has unexpected format: \"" + s + "\". Please report.")
    //            }
    //            ModuleAttributes(someScala, None)
    //          }
    //        }
    //      )
    //    )))
    log.debug(q.toString)
    q
  }
}

object AetherBuildSystem {
  def getProjectModuleID(config: ProjectBuildConfig) = {
    if (!config.uri.startsWith("aether:"))
      sys.error("Fatal: the uri in Ivy project " + config.name + " does not start with \"aether:\"")
    val module = config.uri.substring(7)
    ModuleRevisionId.parse(module)
  }
}
