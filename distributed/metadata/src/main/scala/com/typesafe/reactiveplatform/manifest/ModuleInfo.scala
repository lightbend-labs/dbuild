package com.typesafe.reactiveplatform.manifest

/**
* Represents the information for a given Ivy module within the typesafe reactive-platform.
*/
case class ModuleInfo(
  organization: String,
  name: String,
  version: String,
  cross: CrossBuildProperties)

// TODO- Hard-coded or loose map?
case class CrossBuildProperties(scalaVersion: Option[String], sbtVersion: Option[String])