package com.typesafe.dbuild.support.nil

import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.project.resolve.ProjectResolver
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