package com.typesafe.dsbt

import sbt._

object DistributedBuildPlugin extends Plugin {
  override def buildSettings = DependencyAnalysis.printSettings
}