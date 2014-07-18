package com.typesafe.reactiveplatform.manifest

/**
* Represents a manifest of all information included in a
* typesafe-reactive-platform build.
* @param trp Information about the platform, such as end of life, release date, etc.
* @param modules Information about modules included in the platofrm, including all
* necessary information to discover the module and force a revision
* in the user build.
*/
case class Manifest(trp: PlatformInfo, modules: Seq[ModuleInfo])