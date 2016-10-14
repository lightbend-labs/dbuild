package com.typesafe.dbuild.support.aether

import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.adapter.Adapter
import Adapter.Path._
import Adapter.IO
import Adapter.syntaxio._
import com.typesafe.dbuild.project.resolve.ProjectResolver
import java.io.File
import org.apache.ivy.core.module.id.{ ModuleId, ModuleRevisionId }
import java.text.DateFormat
import java.util.Locale
import java.util.TimeZone
import javax.mail.internet.MailDateFormat

/**
 * This class knows how to resolve the 'aether' uri scheme. It usually doesn't
 * do anything, since the build system will call Aether to fetch the needed files;
 * however, in case of snapshots, it should try to determine which is the most recent
 * snapshot, in order to build a new and unique ProjectBuildConfig.
 */
class AetherProjectResolver(repos: List[xsbti.Repository]) extends ProjectResolver {
  def canResolve(configUri: String): Boolean = {
    val uri = new java.net.URI(configUri)
    uri.getScheme == "aether"
  }

  // This is our chance to fetch changing snapshots; if we don't make ProjectBuildConfig
  // unique, extraction will think '-SNAPSHOT' never changes, and incorrectly use its cache.
  def resolve(config: ProjectBuildConfig, baseDir: File, log: Logger): ProjectBuildConfig = {
    // clean the directory content, just in case there are leftovers
    // (we do not really need to re-resolve non-snapshot artifacts, but let's be conservative)
    IO.delete(baseDir.*("*").get)

    val modRevId = AetherBuildSystem.getProjectModuleID(config)
    val revision = modRevId.getRevision
    if (revision.endsWith("-SNAPSHOT")) {
      // TODO: deal with shapshots. Use a marker, see the Ivy build system as a reference
      config
    } else config
  }
}
