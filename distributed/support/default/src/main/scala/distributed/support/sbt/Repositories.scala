package distributed
package support
package sbt

import project.model._
import _root_.java.io.File
import _root_.java.net.URI
import _root_.sbt.IO

object Repositories {
  val ivyPattern = "[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"  
  def writeRepoFile(config: File, repositories: (String, String)*): Unit = {
    val sb = new StringBuilder("[repositories]\n")
    for((name, uri) <- repositories) {
      sb append (" ivy-%s: %s, %s\n" format(name, uri, ivyPattern))
      sb append (" mvn-%s: %s\n" format(name, uri))
    }
    // TODO - move these into default config or something....
    // First check for local.
    sb append  "  local\n"

    // TODO - these are here just for debugging (Toni)
    sb append ("  roverz: http://localhost:8088/artifactory/tonirepo/temp-distributed-build-snapshots, %s\n" format (ivyPattern))
    sb append  "  rover: http://localhost:8088/artifactory/repo\n"
    sb append ("  rover-typesafe-ivy-releases: http://localhost:8088/artifactory/typesafe-ivy-releases, %s\n" format (ivyPattern))

    // the rest
    sb append  "  maven-central\n"
    sb append  "  sonatype-snapshots: https://oss.sonatype.org/content/repositories/snapshots\n"
    sb append  "  java-annoying-cla-shtuff: http://download.java.net/maven/2/\n"
    sb append ("  typesafe-releases: http://typesafe.artifactoryonline.com/typesafe/releases\n")
    sb append ("  typesafe-ivy-releases: http://typesafe.artifactoryonline.com/typesafe/ivy-releases, %s\n" format (ivyPattern))
    sb append ("  dbuild-snapshots: http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots, %s\n" format (ivyPattern))
    sb append ("  sbt-plugin-releases: http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases, %s\n" format (ivyPattern))
    // This is because the git plugin is too prevalent...
    sb append  "  jgit-repo: http://download.eclipse.org/jgit/maven\n"
    sb append  "  scala-fresh-2.10.x: http://typesafe.artifactoryonline.com/typesafe/scala-fresh-2.10.x/\n"
    IO.write(config, sb.toString)
  } 
}