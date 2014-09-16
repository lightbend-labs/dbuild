package distributed.support.test

import distributed.logging.Logger
import distributed.project.model._
import distributed.project.resolve.ProjectResolver
import java.io.File
import _root_.java.net.URI
import sbt.IO
import sbt.Path._

/**
 * The test resolver does absolutely nothing; however, it fails every now and then (for testing).
 */
class TestProjectResolver() extends ProjectResolver {
  def canResolve(uri: String): Boolean = {
    uri == "test" || uri.startsWith("test:")
  }

  def resolve(config: ProjectBuildConfig, baseDir: File, log: Logger): ProjectBuildConfig = {
    // scrub the whole content before returning
    IO.delete(baseDir.*("*").get)

    val rand = new java.util.Random
    // abort resolution 10% of the times
    if (rand.nextInt(10)==0)
      throw new Exception("Couldn't resolve, today..!")
    // rebuild 50% of the times
    if (rand.nextInt(2)==0) config.copy(setVersion=Some((rand.nextInt(80000)).toString)) else config

  }
}