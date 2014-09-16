package com.typesafe.dbuild.support

object NameFixer {
  // Remove scala version from names so we can do cross-compile magikz.
  val ScalaVersioned = new util.matching.Regex("(.+)_((\\d+)\\.(\\d+)(.+))")
  def fixName(name: String): String = name match {
    case ScalaVersioned(name, _*) => name
    case name => name
  }
}