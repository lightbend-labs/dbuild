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
import scala.collection.JavaConversions._
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

import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.{ DefaultArtifact => AetherDefaultArtifact }
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.deployment.DeployRequest
import org.eclipse.aether.deployment.DeploymentException
import org.eclipse.aether.graph.Dependency
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

class ConsoleRepositoryListener(log: Logger) extends AbstractRepositoryListener {
  override def artifactDeployed(event: RepositoryEvent): Unit =
    log.info("Deployed " + event.getArtifact() + " to " + event.getRepository());

  override def artifactDeploying(event: RepositoryEvent): Unit =
    log.info("Deploying " + event.getArtifact() + " to " + event.getRepository());

  override def artifactDescriptorInvalid(event: RepositoryEvent): Unit =
    log.info("Invalid artifact descriptor for " + event.getArtifact() + ": "
      + event.getException().getMessage());

  override def artifactDescriptorMissing(event: RepositoryEvent): Unit =
    log.info("Missing artifact descriptor for " + event.getArtifact());

  override def artifactInstalled(event: RepositoryEvent): Unit =
    log.info("Installed " + event.getArtifact() + " to " + event.getFile());

  override def artifactInstalling(event: RepositoryEvent): Unit =
    log.info("Installing " + event.getArtifact() + " to " + event.getFile());

  override def artifactResolved(event: RepositoryEvent): Unit =
    log.info("Resolved artifact " + event.getArtifact() + " from " + event.getRepository());

  override def artifactDownloading(event: RepositoryEvent): Unit =
    log.info("Downloading artifact " + event.getArtifact() + " from " + event.getRepository());

  override def artifactDownloaded(event: RepositoryEvent): Unit =
    log.info("Downloaded artifact " + event.getArtifact() + " from " + event.getRepository());

  override def artifactResolving(event: RepositoryEvent): Unit =
    log.info("Resolving artifact " + event.getArtifact());

  override def metadataDeployed(event: RepositoryEvent): Unit =
    log.info("Deployed " + event.getMetadata() + " to " + event.getRepository());

  override def metadataDeploying(event: RepositoryEvent): Unit =
    log.info("Deploying " + event.getMetadata() + " to " + event.getRepository());

  override def metadataInstalled(event: RepositoryEvent): Unit =
    log.info("Installed " + event.getMetadata() + " to " + event.getFile());

  override def metadataInstalling(event: RepositoryEvent): Unit =
    log.info("Installing " + event.getMetadata() + " to " + event.getFile());

  override def metadataInvalid(event: RepositoryEvent): Unit =
    log.info("Invalid metadata " + event.getMetadata());

  override def metadataResolved(event: RepositoryEvent): Unit =
    log.info("Resolved metadata " + event.getMetadata() + " from " + event.getRepository());

  override def metadataResolving(event: RepositoryEvent): Unit =
    log.info("Resolving metadata " + event.getMetadata() + " from " + event.getRepository());
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
  private val downloads = new scala.collection.mutable.HashMap[TransferResource, Long] with scala.collection.mutable.SynchronizedMap[TransferResource, Long]

  private var lastLength: Int = 0

  override def transferInitiated(event: TransferEvent): Unit = {
    val message = if (event.getRequestType() == TransferEvent.RequestType.PUT) "Uploading" else "Downloading"
    log.info(message + ": " + event.getResource().getRepositoryUrl() + event.getResource().getResourceName());
  }

  override def transferProgressed(event: TransferEvent): Unit =
    {
      val resource = event.getResource()
      downloads.put(resource, event.getTransferredBytes())

      val buffer = new StringBuilder(64)

      downloads.foreach {
        case (total, complete) =>
          buffer.append(getStatus(complete, total.getContentLength)).append("  ")
      }

      val padLen = lastLength - buffer.length
      lastLength = buffer.length
      pad(buffer, padLen)
      buffer.append('\r')

      log.debug(buffer.toString);
    }

  private def toKB(bytes: Long): Long = (bytes + 1023) / 1024;

  private def getStatus(complete: Long, total: Long): String =
    {
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

  // TODO: Scala-ize a bit better
  private def pad(buffer: StringBuilder, inSpaces: Int): Unit = {
    var spaces = inSpaces
    val block = "                                        ";
    while (spaces > 0) {
      val n = Math.min(spaces, block.length)
      buffer.append(block.toArray[Char], 0, n)
      spaces = spaces - n
    }
  }

  private def transferCompleted(event: TransferEvent): Unit = {
    downloads.remove(event.getResource())

    val buffer = new StringBuilder(64)
    pad(buffer, lastLength)
    buffer.append('\r')
    log.debug(buffer.toString);
  }

  def dumpException(event: TransferEvent): Unit = {
    val errors = new java.io.StringWriter
    val pw = new java.io.PrintWriter(errors)
    event.getException.printStackTrace(pw)
    log.error(errors.toString)
  }

  override def transferCorrupted(event: TransferEvent): Unit = dumpException(event)

  override def transferFailed(event: TransferEvent): Unit =
    {
      transferCompleted(event)
      event.getException match {
        case e: MetadataNotFoundException =>
        case _ => dumpException(event)
      }
    }

  override def transferSucceeded(event: TransferEvent): Unit =
    {
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
    });

    locator.getService(classOf[RepositorySystem]);
  }

  def newRepositorySystemSession(system: RepositorySystem, log: Logger): DefaultRepositorySystemSession = {
    val session = MavenRepositorySystemUtils.newSession();

    val localRepo = new LocalRepository("target/local-repo");
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

    session.setTransferListener(new ConsoleTransferListener(log));
    session.setRepositoryListener(new ConsoleRepositoryListener(log));

    // uncomment to generate dirty trees
    // session.setDependencyGraphTransformer( null );

    session;
  }

  def newRepositories(system: RepositorySystem, session: RepositorySystemSession): Seq[RemoteRepository] = {
    Seq(newCentralRepository())
  }

  def newCentralRepository(): RemoteRepository = {
    new RemoteRepository.Builder("central", "default", "http://central.maven.org/maven2/").build();
  }
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

  def extractDependencies(extractionConfig: ExtractionConfig, baseDir: File, extractor: Extractor, log: Logger, debug: Boolean): ExtractedBuildMeta = {
    val config = extractionConfig.buildConfig
    // TODO: extract the dependencies for real using Aether. It should be easy to do, see:
    // http://git.eclipse.org/c/aether/aether-demo.git/tree/aether-demo-snippets/src/main/java/org/eclipse/aether/examples/GetDirectDependencies.java
    // For now, let's pretend there are no further dependencies
    val module = config.uri.substring(7)
    val modRevId = ModuleRevisionId.parse(module)
    ExtractedBuildMeta(modRevId.getRevision, Seq.empty, Seq.empty)
    // (version: String, projects: Seq[Project], subproj: Seq[String] = Seq.empty)
    val q = ExtractedBuildMeta(modRevId.getRevision, Seq(Project(fixName(modRevId.getName()), modRevId.getOrganisation(),
      //  artifacts: Seq[ProjectRef],
      Seq.empty /*firstNode.getAllArtifacts.toSeq.map(artifactToProjectRef).distinct */ ,
      //  dependencies: Seq[ProjectRef])
      Seq.empty /* TODO: insert actual dependencies */ )))
    log.debug(q.toString)
    q
  }

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

    val module = modRevId

    val version = input.version
    log.info("Will publish as:")
    log.info("  " + module.getOrganisation + "#" + module.getName + ";" + version)

    // TODO: implement the rename and pom rewriting. That may be absolutely necessary, as we need a different
    // version number when bootstrapping the Scala compiler.
    if (version != module.getRevision()) {
      sys.error("Unsupported: we asked the mini-Maven build system to republish " + module.getOrganisation + "#" +
        module.getName + ";" + module.getRevision +
        " as version " + version + ", but the version change code has not been implemented yet.")
    }
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

    val localRepo = input.outRepo
    val modulePluginInfo = pluginAttrs(module)

    // TODO: use sbt's internals to transform a IVY plugin reference into a maven one (with double suffix), if applicable

    import Booter._

    val localRepositoryDir = (baseDir / "aether-repo").getCanonicalPath
    val localRepository = new LocalRepository(localRepositoryDir)

    val repositorySystem = Booter.newRepositorySystem()
    val session: DefaultRepositorySystemSession = Booter.newRepositorySystemSession(repositorySystem, log)
    session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, localRepository))

    //////////
    // This is where the actual artifact we want is prepared for resolution/downloading
    val dependency =
      new Dependency(new AetherDefaultArtifact(module.getOrganisation, module.getName, "", "jar", module.getRevision),
        "runtime") // FIXME : customize the configuration (compile/test/whatever, compile by default
    //////////

    // repos: List[xsbti.Repository]
    // TODO: grab the list of resolvers (Maven only), and use it here. Prepent the local rematerialized repo (even if
    // (even if now it is empty, we might track the real dependencies at a later time)

    def toMaven(repo: xsbti.Repository) = repo match {
      case m: xsbti.MavenRepository => Some(new RemoteRepository.Builder(m.id, "default", m.url.toString).build())
      case i: xsbti.IvyRepository =>
        log.info("Ivy repository " + i.id + " will be ignored."); None
      case p: xsbti.PredefinedRepository => p.id match {
        // "local" is made to point to the same ivyHome, but nothing is ever published there
        case Local =>
          log.info("The \"local\" predefined Ivy repository will be ignored."); None
        case MavenLocal => None
        // We are already pointing to a custom Local Repository, above, and we are not going to touch the
        // global one. It would be something like:
        // "Maven2 Local", System.getProperty("user.home") + "/.m2/repository/"
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

    val collectRequest = new CollectRequest()
    collectRequest.setRoot(dependency)
    repos.map(toMaven).flatten.foreach { collectRequest.addRepository }

    val dependencyRequest = new DependencyRequest()
    dependencyRequest.setCollectRequest(collectRequest)

    val rootNode = repositorySystem.resolveDependencies(session, dependencyRequest).getRoot()
    val nlg = new PreorderNodeListGenerator();
    rootNode.accept(nlg);

    // The result is made up of:
    // - rootNode
    // - resolvedFiles
    // - resolvedClassPath
    import org.eclipse.aether.graph.DependencyNode;
    case class AetherResult(root: DependencyNode, resolvedFiles: Seq[java.io.File], resolvedClassPath: String)
    val result = new AetherResult(rootNode, nlg.getFiles().toSeq, nlg.getClassPath());

    log.info("Result: "+result)
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
