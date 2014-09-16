package com.typesafe.dbuild

import sbt._

object DistributedBuildPlugin extends Plugin {
  override def buildSettings = (
      DistributedRunner.buildSettings
    )
  override def projectSettings = DistributedRunner.projectSettings
}