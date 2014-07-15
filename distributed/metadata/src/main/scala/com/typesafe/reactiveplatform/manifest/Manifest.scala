package com.typesafe.reactiveplatform.manifest

/**
* Represents a manifest of all information included in a
* typesafe-reactive-platform build.
*/
case class Manifest(trp: PlatformInfo, modules: Seq[ModuleInfo])