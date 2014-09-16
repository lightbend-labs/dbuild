package distributed
package support
package sbt

import project.model._
import distributed.project.model.ExtraConfig

case class SbtBuildConfig(
  config: SbtExtraConfig,
  crossVersion: Seq /*Levels*/ [String],
  checkMissing: Seq /*Levels*/ [Boolean],
  info: BuildInput)
