package com.typesafe.dbuild.plugin

import sbt._
import com.typesafe.dbuild.model

object DBuildKeys {
  // TODO - make a task that generates this metadata and just call it!
  type ArtifactMap = Seq[model.ArtifactLocation]
  val extractArtifacts = TaskKey[ArtifactMap]("dbuild-extract-artifacts")
  // Used during the index generation
  val moduleInfo = TaskKey[com.typesafe.dbuild.manifest.ModuleInfo]("module-info")
}