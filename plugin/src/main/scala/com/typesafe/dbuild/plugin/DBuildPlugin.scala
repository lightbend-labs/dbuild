package com.typesafe.dbuild.plugin

import sbt._

object DBuildPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin
  override def buildSettings = (
      DBuildRunner.buildSettings
    )
  override def projectSettings = DBuildRunner.projectSettings
}
