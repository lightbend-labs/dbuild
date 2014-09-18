package com.typesafe.dbuild.support

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.project.BuildSystem
import com.typesafe.dbuild.project.dependencies.Extractor
import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.project.build.LocalBuildRunner

abstract class BuildSystemCore extends BuildSystem[Extractor, LocalBuildRunner] {
  /**
   * See the docs in BuildSystem.
   * 
   * The default implementation simply resolves the main URI. In case the build system 
   * supports nested projects, the implementation should be overridden so that, in addition,
   * all of the nested projects are recursively resolved in turn.
   */
  def resolve(config: ProjectBuildConfig, dir: _root_.java.io.File, extractor: Extractor, log: Logger): ProjectBuildConfig =
    extractor.resolver.resolve(config, dir, log)
}