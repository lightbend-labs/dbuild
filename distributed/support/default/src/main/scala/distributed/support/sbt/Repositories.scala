package distributed
package support
package sbt

import project.model._
import _root_.java.io.File
import _root_.java.net.URI
import _root_.sbt.IO

object Repositories {
  val ivyPattern = "[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"  
  def writeRepoFile(repos:List[xsbti.Repository], config: File, repositories: (String, String)*): Unit = {
    val sb = new StringBuilder("[repositories]\n")
    for((name, uri) <- repositories) {
      sb append (" ivy-%s: %s, %s\n" format(name, uri, ivyPattern))
      sb append (" mvn-%s: %s\n" format(name, uri))
    }
    repos foreach {
      case m:xsbti.MavenRepository => sb append ("  "+m.id+": "+m.url+"\n")
      case i:xsbti.IvyRepository => sb append ("  "+i.id+": "+i.url+", "+i.ivyPattern+"\n")
      case p:xsbti.PredefinedRepository => sb append ("  "+p.id+"\n")
    }
    IO.write(config, sb.toString)
  } 
}
