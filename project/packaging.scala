import sbt._
import com.typesafe.sbt.packager.Keys._
import sbt.Keys._
import com.typesafe.sbt.SbtNativePackager._
import com.typesafe.sbt.S3Plugin
import com.typesafe.sbt.S3Plugin.S3._
import com.typesafe.sbt.packager.universal.Keys.packageZipTarball

object Packaging {

  def mapArt[A](key:sbt.TaskKey[_], kind: String) =
     // cheat a little: by setting the classifier to the version number, we can
     // publish to Ivy the tgz/zip with the full name, like "dbuild-0.8.1.tgz".
     artifact in (Universal, key) <<= (artifact in (Universal, key), version) {
       (a,v) => a.copy(`type` = kind, extension = kind, `classifier` = Some(v))
     }

  def settings: Seq[Setting[_]] = packagerSettings ++ S3Plugin.s3Settings ++ Seq(
     organization := "com.typesafe.dbuild",
     name := "dbuild",
     wixConfig := <wix/>,
     maintainer := "Antonio Cunei <antonio.cunei@typesafe.com>",
     packageSummary := "Multi-project builder.",
     packageDescription := """A multi-project builder capable of gluing together a set of related projects.""",
     mappings in Universal <+= SbtSupport.sbtLaunchJar map { jar =>
       jar -> "bin/sbt-launch.jar"
     },
     name in Universal <<= (name,version).apply((n,v) => (n+"-"+v)),
     rpmRelease := "1",
     rpmVendor := "typesafe",
     rpmUrl := Some("http://github.com/typesafehub/distributed-build"),
     rpmLicense := Some("BSD"),

     host in upload := "downloads.typesafe.com",
     mappings in upload <<= (packageZipTarball in Universal, packageBin in Universal, name, version) map
       {(tgz,zip,n,v) => Seq(tgz,zip) map {f=>(f,n+"/"+v+"/"+f.getName)}},
     progress in upload := true,
     credentials += Credentials(Path.userHome / ".s3credentials"),
     upload <<= upload dependsOn (clean),

     publishArtifact in Compile := false,

     // NB: A must be executed before both packageZipTarball and packageZipTarball,
     // otherwise Universal may end up using outdated files.

     publishLocal <<= publishLocal dependsOn (clean),
     publish <<= publish dependsOn (clean),

     publishMavenStyle := false,
     autoScalaLibrary := false,

     mapArt(packageZipTarball, "tgz"),
     mapArt(packageBin, "zip"),

     crossPaths := false
  ) ++
    addArtifact(artifact in (Universal, packageZipTarball), packageZipTarball in Universal) ++
    addArtifact(artifact in (Universal, packageBin), packageBin in Universal)

  def makeDRepoProps(t: File, src: File, sv: String, v: String): (File, String) = makeProps(t,src,sv,v,"repo","distributed.repo.core.SbtRepoMain")
  def makeDbuildProps(t: File, src: File, sv: String, v: String): (File, String) = makeProps(t,src,sv,v,"build","distributed.build.SbtBuildMain")

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
  name: d-%s
  version: %s
  class: %s
  cross-versioned: true
  components: xsbti

[repositories]
  local
  maven-central
  sonatype-snapshots: https://oss.sonatype.org/content/repositories/snapshots
  sonatype-releases: https://oss.sonatype.org/content/repositories/releases
  java-annoying-cla-shtuff: http://download.java.net/maven/2/
  typesafe-releases: http://typesafe.artifactoryonline.com/typesafe/releases
  typesafe-ivy-releases: http://typesafe.artifactoryonline.com/typesafe/ivy-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  dbuild-snapshots: http://typesafe.artifactoryonline.com/typesafe/temp-distributed-build-snapshots, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  sbt-plugin-releases: http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  jgit-repo: http://download.eclipse.org/jgit/maven
  scala-fresh-2.10.x: http://typesafe.artifactoryonline.com/typesafe/scala-fresh-2.10.x/

[boot]
 directory: ${dbuild.boot.directory-${dbuild.global.base-${user.home}/.dbuild}/boot/}

[ivy]
  ivy-home: ${user.home}/.dbuild/ivy/
  checksums: ${sbt.checksums-sha1,md5}
  override-build-repos: ${sbt.override.build.repos-false}
""" format(sv, name, v, clazz))
    tprops -> ("bin/d"+name+".properties")
  }

}
