package distributed
package support
package sbt

import project.model._
import distributed.project.model.ExtraConfig

case class SbtBuildConfig(config: SbtExtraConfig, info: BuildInput, buildOptions: BuildOptions)
