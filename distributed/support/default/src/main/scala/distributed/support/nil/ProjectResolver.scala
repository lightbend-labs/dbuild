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

  def resolve(config: ProjectBuildConfig, baseDir: File, log: Logger): ProjectBuildConfig = {
    val rand = new java.util.Random
    // abort resolution 10% of the times
    if (rand.nextInt(10)==0)
      throw new Exception("Couldn't resolve, today..!")
    // rebuild 50% of the times
    if (rand.nextInt(2)==0) config.copy(setVersion=Some((rand.nextInt(80000)).toString)) else config

  }
}