package com.typesafe.dbuild.plugin

import sbt._

object DBuildPlugin extends Plugin {
  override def buildSettings = (
      DBuildRunner.buildSettings
    )
  override def projectSettings = DBuildRunner.projectSettings
}