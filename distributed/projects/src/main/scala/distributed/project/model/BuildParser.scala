package distributed
package project
package model

import config._
import com.typesafe.config.ConfigObject

object DistributedBuildParser {
  def parseBuildFile(f: java.io.File): DistributedBuildConfig = {
    val r = new java.io.FileReader(f)
    try parseBuild(r)
    finally r.close
  }
  
  def parseBuildString(in: String): DistributedBuildConfig = {
    val r = new java.io.StringReader(in)
    try parseBuild(r)
    finally r.close
  }
  
  def parseBuild(in: java.io.Reader): DistributedBuildConfig = {
    config.parse(in).resolve.root match {
      case DistributedBuildConfig.Configured(build) => build
      case _ => sys.error("Unable to parse build from: "+in)
    }
  }
}