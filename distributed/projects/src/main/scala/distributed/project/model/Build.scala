package distributed
package project
package model

/** Information on how to build a project.  Consists of both distributed build
 * configuration and extracted information.
 */
case class Build(config: BuildConfig, extracted: ExtractedBuildMeta)

/** A distributed build containing projects in *build order*/
case class DistributedBuild(builds: Seq[Build])