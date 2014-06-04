package distributed.support.nil

import distributed.logging.Logger
import distributed.project.model._
import distributed.project.resolve.ProjectResolver
import java.io.File
import sbt.IO
import sbt.Path._

/**
 * The nil resolver does absolutely nothing.
 */
class NilProjectResolver() extends ProjectResolver {
  def canResolve(uri: String): Boolean = {
    uri == "nil" || uri.startsWith("nil:")
  }

  def resolve(config: ProjectBuildConfig, baseDir: File, log: Logger): ProjectBuildConfig = {
    // scrub the whole content before returning
    IO.delete(baseDir.*("*").get)
    config
  }
}