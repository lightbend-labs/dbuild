package com.typesafe.dbuild.plugin

import sbt._
import com.typesafe.dbuild.model

object DistributedBuildKeys {
  // TODO - make a task that generates this metadata and just call it!
  type ArtifactMap = Seq[model.ArtifactLocation]
  val extractArtifacts = TaskKey[ArtifactMap]("distributed-build-extract-artifacts")
  // Used during the index generation
  val moduleInfo = TaskKey[com.typesafe.dbuild.manifest.ModuleInfo]("module-info")
}