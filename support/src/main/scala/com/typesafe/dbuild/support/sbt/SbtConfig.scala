package com.typesafe.dbuild.support.sbt

import com.typesafe.dbuild.model._

case class SbtBuildConfig(
  config: SbtExtraConfig,
  crossVersion: Seq /*Levels*/ [String],
  checkMissing: Seq /*Levels*/ [Boolean],
  rewriteOverrides: Seq /*Levels*/ [Boolean],
  info: BuildInput)
