package distributed.support

import distributed.project.model._
import distributed.project.BuildSystem
import distributed.project.dependencies.Extractor
import distributed.logging.Logger
import distributed.project.build.LocalBuildRunner

trait BuildSystemCore extends BuildSystem[Extractor, LocalBuildRunner] {
  /**
   * See the docs in BuildSystem.
   * 
   * The default implementation simply resolves the main URI. In case the build system 
   * supports nested projects, the implementation should be overridden so that, in addition,
   * all of the nested projects are recursively resolved in turn.
   */
  def resolve(config: ProjectBuildConfig, opts: BuildOptions, dir: _root_.java.io.File, extractor: Extractor, log: Logger): ProjectBuildConfig =
    extractor.resolver.resolve(config, opts, dir, log)
}