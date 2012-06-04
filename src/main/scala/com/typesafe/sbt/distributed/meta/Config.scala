package com.typesafe.sbt.distributed
package meta


/** Information on how to build a project.  Consists of both distributed build
 * configuration and extracted information.
 */
case class Build(config: BuildConfig, extracted: ExtractedBuildMeta)

/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
case class BuildConfig(name: String, system: String, uri: String, directory: String)

trait Dependency {
  def name: String
  def organization: String
  def isBuildable: Boolean
  
  override def toString = 
    (if(isBuildable) "Local(" else "RemoteDep(") + name + ", " +organization+")"
}

/** Represents the *Extracted* metadata of a build.
 */
case class ExtractedBuildMeta(uri: String, projects: Seq[Project]) {
  override def toString = "Build(%s)" format (uri)
}
/** Represents extracted Project information in a build.  A project is akin to a
 * deployed artifact for a given build, and may have dependencies.
 */
case class Project(
    name: String,
    organization: String,
    dependencies: Seq[ProjectDep]) extends Dependency {
  def isBuildable = true
}    
/** A project dep is an extracted *external* build dependency.  I.e. this is a
 * maven/ivy artifact that exists and is built external to a local build.
 */
case class ProjectDep(name: String, organization: String) extends Dependency {
  def isBuildable = false
}



