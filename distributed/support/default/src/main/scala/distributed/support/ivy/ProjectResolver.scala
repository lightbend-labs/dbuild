package distributed.support.ivy

import distributed.logging.Logger
import distributed.project.model._
import distributed.project.resolve.ProjectResolver
import java.io.File
import org.apache.ivy.core.module.id.{ ModuleId, ModuleRevisionId }
import java.text.DateFormat
import java.util.Locale
import java.util.TimeZone
import javax.mail.internet.MailDateFormat

/**
 * This class knows how to resolve the 'ivy' uri scheme. It usually doesn't
 * do anything, since the build system will call Ivy to fetch the needed files;
 * however, in case of snapshots, it tries to determine which is the most recent
 * snapshot, in order to build a new and unique ProjectBuildConfig.
 */
class IvyProjectResolver(repos: List[xsbti.Repository]) extends ProjectResolver {
  def canResolve(configUri: String): Boolean = {
    val uri = new java.net.URI(configUri)
    uri.getScheme == "ivy"
  }

  // This is our chance to fetch changing snapshots; if we don't make ProjectBuildConfig
  // unique, extraction will think '-SNAPSHOT' never changes, and incorrectly use its cache.
  def resolve(config: ProjectBuildConfig, baseDir: File, log: Logger): ProjectBuildConfig = {
    if (!config.uri.startsWith("ivy:"))
      sys.error("Fatal: the uri in Ivy project " + config.name + " must start with the string \"ivy:\"")
    val module = config.uri.substring(4)
    log.debug("requested module is: " + module)
    val modRevId = ModuleRevisionId.parse(module)
    val revision = modRevId.getRevision
    if (revision.endsWith("-SNAPSHOT")) {
      val response = IvyMachinery.resolveIvy(config, baseDir, repos, log, transitive = false)
      val report = response.report
      // We use the snapshotMarker field to store a date string, in order to identify
      // uniquely this particular snapshot. The string is not actively used, but
      // it gets included in the hash calculation, making it unique
      val dateFormat = new MailDateFormat()
      dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"))
      val date = dateFormat.format(report.getAllArtifactsReports()(0).getArtifact.getPublicationDate)
      // even better, in case of unique artifacts we can look at the real origin jar, for diagnostic purposes.
      // Unfortunately. we cannot replace the original revision, as it needs to remain "-SNAPSHOT" for
      // resolution to succeed (the artifacts are published in a /...-SNAPSHOT/ directory).
      val localFile = new File(report.getAllArtifactsReports()(0).getArtifactOrigin.getLocation).getName.replaceAll("(\\.[^\\.]*$)", "")
      val baseName = modRevId.getName + "-"
      val newRevision = if (localFile.startsWith(baseName)) {
        localFile.substring(baseName.length)
      } else revision
      // this will turn com.typesafe.sbt#incremental-compiler;0.13.0-on-2.10.2-for-IDE-SNAPSHOT
      // into the actual com.typesafe.sbt#incremental-compiler;0.13.0-on-2.10.2-for-IDE-20130725.100115-3
      val newModRevId = new ModuleRevisionId(modRevId.getModuleId, modRevId.getBranch, newRevision)
      log.info("The resolved SNAPSHOT is: " + newModRevId + ", published on: " + date)
      config.copy(extra=Some(IvyMachinery.ivyExpandConfig(config).copy(snapshotMarker = Some(date))))
    } else config
  }
}