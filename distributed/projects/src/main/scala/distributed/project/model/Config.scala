package distributed
package project
package model


/** The initial configuration for a build. */
case class DistributedBuildConfig(projects: Seq[BuildConfig])
    
/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
case class BuildConfig(name: String, 
    system: String, 
    uri: String, 
    directory: String)


