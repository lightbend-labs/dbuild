import sbt._
import com.typesafe.packager.Keys._
import sbt.Keys._
import com.typesafe.packager.PackagerPlugin._

object Packaging {


  def settings: Seq[Setting[_]] = packagerSettings ++ Seq(
     name := "dsbt",
     wixConfig := <wix/>,
     maintainer := "Josh Suereth <joshua.suereth@typesafe.com>",
     packageSummary := "Multi-project builder.",
     packageDescription := """A multi-project builder capable of gluing together a set of related projects.""",
     mappings in Universal <+= SbtSupport.sbtLaunchJar map { jar =>
       jar -> "bin/sbt-launch.jar"
     },
     rpmRelease := "1",
     rpmVendor := "typesafe",
     rpmUrl := Some("http://github.com/scala/scala-dist"),
     rpmLicense := Some("BSD")
  )

  
  // TODO - Share functionality with other guy.
  def makeDRepoProps(t: File, src: File, sv: String, v: String): (File, String) = {
    val tdir = t / "generated-sources"
    if(!tdir.exists) tdir.mkdirs()
    val tprops = tdir / "drepo.properties"
    // TODO - better caching
    if(!tprops.exists) IO.write(tprops, """
[scala]
  version: %s

[app]
  org: com.typesafe.dsbt
  name: d-repo
  version: %s
  class: distributed.repo.core.SbtRepoMain
  cross-versioned: true
  components: xsbti

[repositories]
  local
  maven-central
  typesafe-releases: http://typesafe.artifactoryonline.com/typesafe/releases
  typesafe-ivy-releases: http://typesafe.artifactoryonline.com/typesafe/ivy-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  dbuild-snapshots: http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]

[boot]
 directory: ${dsbt.boot.directory-${dsbt.global.base-${user.home}/.dsbt}/boot/}

[ivy]
  ivy-home: ${user.home}/.dsbt/ivy/
  checksums: ${sbt.checksums-sha1,md5}
  override-build-repos: ${sbt.override.build.repos-false}
""" format(sv, v))
    tprops -> "bin/drepo.properties"
  }
  

  def makeDsbtProps(t: File, src: File, sv: String, v: String): (File, String) = {
    val tdir = t / "generated-sources"
    if(!tdir.exists) tdir.mkdirs()
    val tprops = tdir / "dsbt.properties"
    // TODO - better caching
    if(!tprops.exists) IO.write(tprops, """
[scala]
  version: %s

[app]
  org: com.typesafe.dsbt
  name: d-build
  version: %s
  class: distributed.build.SbtBuildMain
  cross-versioned: true
  components: xsbti

[repositories]
  local
  maven-central
  typesafe-releases: http://typesafe.artifactoryonline.com/typesafe/releases
  typesafe-ivy-releases: http://typesafe.artifactoryonline.com/typesafe/ivy-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  dbuild-snapshots: http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]

[boot]
 directory: ${dsbt.boot.directory-${dsbt.global.base-${user.home}/.dsbt}/boot/}

[ivy]
  ivy-home: ${user.home}/.dsbt/ivy/
  checksums: ${sbt.checksums-sha1,md5}
  override-build-repos: ${sbt.override.build.repos-false}
""" format(sv, v))
    tprops -> "bin/dsbt.properties"
  }
}
