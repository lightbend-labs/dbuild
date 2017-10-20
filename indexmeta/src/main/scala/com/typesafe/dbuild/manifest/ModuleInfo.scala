package com.typesafe.dbuild.manifest

/**
* Represents the information for a given Ivy module within the Lightbend Reactive Platform (v1, no longer in use)
*
* - Organization should be the SAME as the groupId/organizatoin used in ivy.
* - name should be the SAME as the name used in sbt. This means, any cross-version shading
* should be removed. E.g. akka_2.10 becomes just Akka.
* - cross This datatype will contain all cross-versioning information required to LOOKUP (not build)
* the module appropriately. If the name needs to be shaded, that aspect of the repository
* lookup will happen. Also, two ModuleInfo's with *different* crossbuild properties can
* coexist in the platform and will be treated differently.
*/
case class ModuleInfo(
  organization: String,
  name: String,
  version: String,
  attributes: ModuleAttributes)

/** This represents the cross-building components of modules which will be used during
* resolution.
*
* While, ideally, this information would be uniformly recorded and used, it's actually
* a hodgepodge of implementations across the various maven/ivy codebases. We
* collect the standard set of projects below:
*
* - Libraries without cross versioning (e.g. JUnit) -
* + The scalaVersion + sbtVersion should be "none"
* - Libraries published to Maven/Ivy with "shaded" module name
* + The scalaVersion string must match the version encoded in the module name.
* e.g. akka_2.10 would have a scalaVersion set to Some("2.10"), while
* akka_2.9.2 would have a scalaVersion set to Some("2.9.2")
* - Sbt plugins, which use extra attributes (the right way) to store these versions,
* should have the same string stored in the `e:sbtVersion` and `e:scalaVersion`
* fields here, e.g. a vanilla sbt plugin built for sbt 0.13 would have:
* scalaVersion=Some("2.10"), sbtVersion=Some("0.13")
* while a vanilla sbt plugin built for sbt 0.12 would have:
* scalaVersion=Some("2.9.2"), sbtVersion=Some("0.12")
* and finally a vanilla sbt plugin built for sbt 0.11.3 would have:
* scalaVersion=Some("2.9.1"), sbtVersion=Some("0.11.3")
*
*/
case class ModuleAttributes(scalaVersion: Option[String], sbtVersion: Option[String])
