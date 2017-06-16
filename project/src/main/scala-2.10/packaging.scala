import sbt._
import com.typesafe.sbt.packager.Keys._
import sbt.Keys._
import com.typesafe.sbt.SbtNativePackager._
import com.typesafe.sbt.S3Plugin
import com.typesafe.sbt.S3Plugin.S3._
import com.typesafe.sbt.packager.universal.Keys.packageZipTarball
import scala.concurrent.ExecutionContext.Implicits.global
import DbuildLauncher.{launcherVersion,uri}

object Packaging {

  def mapArt[A](key:sbt.TaskKey[_], kind: String) =
     // cheat a little: by setting the classifier to the version number, we can
     // publish to Ivy the tgz/zip with the full name, like "dbuild-0.8.1.tgz".
     artifact in (Universal, key) :=
       (artifact in (Universal, key)).value.copy(`type` = kind, extension = kind, `classifier` = Some(version.value))

  def settings(build:Project, repo:Project): Seq[Setting[_]] = packagerSettings ++ S3Plugin.s3Settings ++
     SbtSupport.buildSettings ++ packagerSettings ++ Seq(
     organization := "com.typesafe.dbuild",
     name := "dbuild",
     wixConfig := <wix/>,
     maintainer := "Antonio Cunei <antonio.cunei@lightbend.com>",
     packageSummary := "Multi-project builder.",
     packageDescription := """A multi-project builder capable of glueing together a set of related projects.""",
     name in Universal := name.value + "-" + version.value,
     rpmRelease := "1",
     rpmVendor := "typesafe",
     rpmUrl := Some("http://github.com/typesafehub/dbuild"),
     rpmLicense := Some("BSD"),

     // NB: A clean must be executed before both packageZipTarball and packageZipTarball,
     // otherwise Universal may end up using outdated files.
     // The command "release" in root will perform a clean, followed by a publish.

     publishArtifact in Compile := false,

     publishMavenStyle := false,
     autoScalaLibrary := false,

     mapArt(packageZipTarball, "tgz"),
     mapArt(packageBin, "zip"),

     crossPaths := false

  ) ++
  addArtifact(artifact in (Universal, packageZipTarball), packageZipTarball in Universal) ++
  addArtifact(artifact in (Universal, packageBin), packageBin in Universal) ++
  Seq(mappings in Universal ++= Seq(
        Packaging.makeDBuildProps(target.value, sourceDirectory.value, (scalaVersion in build).value, (version in build).value),
        Packaging.makeDRepoProps(target.value, sourceDirectory.value, (scalaVersion in build).value, (version in build).value),
        Packaging.dbuildLauncher(target.value, streams.value.log, (version in build).value)
      )
  )

  def dbuildLauncher(target: File, log: Logger, dbuildVersion: String) = {
    val tdir = target / "dbuild-launcher"
    if(!tdir.exists) tdir.mkdirs()
    val file = tdir / "dbuild-launcher.jar"
    log.info("Downloading dbuild launcher "+ uri +" to "+ file.getAbsolutePath() +"...")
    val ht = new com.typesafe.dbuild.http.HttpTransfer(dbuildVersion)
    try {
      ht.download(uri, file) // uri from DbuildLauncher.scala
    } finally {
      ht.close()
    }
    file -> "bin/dbuild-launcher.jar"
  }

  def makeDRepoProps(t: File, src: File, sv: String, v: String): (File, String) = makeProps(t,src,sv,v,"repo","com.typesafe.dbuild.repo.core.SbtRepoMain")
  def makeDBuildProps(t: File, src: File, sv: String, v: String): (File, String) = makeProps(t,src,sv,v,"build","com.typesafe.dbuild.build.SbtBuildMain")

  private def makeProps(t: File, src: File, sv: String, v: String, name:String, clazz:String): (File, String) = {
    val tdir = t / "generated-sources"
    if(!tdir.exists) tdir.mkdirs()
    val tprops = tdir / ("d"+name+".properties")
    // TODO - better caching
    if(!tprops.exists) IO.write(tprops, """
[scala]
  version: %s

[app]
  org: com.typesafe.dbuild
  name: %s
  version: %s
  class: %s
  cross-versioned: binary
  components: xsbti

[repositories]
  local
  maven-central
  sonatype-snapshots: https://oss.sonatype.org/content/repositories/snapshots
  sonatype-releases: https://oss.sonatype.org/content/repositories/releases
  jcenter: https://jcenter.bintray.com/
  java-annoying-cla-shtuff: http://download.java.net/maven/2/
  typesafe-releases: http://repo.typesafe.com/typesafe/releases
  typesafe-ivy-releases: http://repo.typesafe.com/typesafe/ivy-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  typesafe-ivy-snapshots: http://repo.typesafe.com/typesafe/ivy-snapshots, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  sbt-plugin-releases: http://repo.scala-sbt.org/scalasbt/sbt-plugin-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  jgit-repo: http://download.eclipse.org/jgit/maven
  scala-fresh-2.10.x: http://repo.typesafe.com/typesafe/scala-fresh-2.10.x/

[boot]
 directory: ${dbuild.boot.directory-${dbuild.global.base-${user.home}/.dbuild}/boot/}

[ivy]
  ivy-home: ${dbuild.ivy.home-${user.home}/.ivy2/}
  checksums: ${sbt.checksums-sha1,md5}
  override-build-repos: ${sbt.override.build.repos-false}
  repository-config: ${sbt.repository.config-${sbt.global.base-${user.home}/.sbt}/repositories}
""" format(sv, name, v, clazz))
    tprops -> ("bin/d"+name+".properties")
  }

}
