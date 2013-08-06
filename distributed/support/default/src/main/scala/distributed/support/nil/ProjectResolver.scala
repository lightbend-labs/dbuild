package distributed.support.nil

import distributed.logging.Logger
import distributed.project.model._
import distributed.project.resolve.ProjectResolver
import java.io.File

/**
 * The nil resolver does absolutely nothing.
 */
class NilProjectResolver() extends ProjectResolver {
  def canResolve(config: ProjectBuildConfig): Boolean = {
    val uri = new java.net.URI(config.uri)
    uri.getScheme == "nil"
  }

  def resolve(config: ProjectBuildConfig, baseDir: File, log: Logger): ProjectBuildConfig = config
}