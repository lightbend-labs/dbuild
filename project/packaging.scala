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
     packageDescription := """A multi-project builder capable of glueing together a set of related projects.""",
     mappings in Universal <+= SbtSupport.sbtLaunchJar map { jar =>
       jar -> "bin/sbt-launch.jar"
     },
     name in Universal <<= (name,version).apply((n,v) => (n+"-"+v)),
     rpmRelease := "1",
     rpmVendor := "typesafe",
     rpmUrl := Some("http://github.com/typesafehub/dbuild"),
     rpmLicense := Some("BSD"),

     // NB: A clean must be executed before both packageZipTarball and packageZipTarball,
     // otherwise Universal may end up using outdated files.
     // The command "release" in root will perform a clean, followed by a publish.

     // S3 stuff
     host in upload := "downloads.typesafe.com",
     mappings in upload <<= (packageZipTarball in Universal, packageBin in Universal, name, version, scalaVersion) map
       {(tgz,zip,n,v,sv) => if(sv.startsWith("2.10")) Seq.empty else Seq(tgz,zip) map {f=>(f,n+"/"+v+"/"+f.getName)}},
     progress in upload := true,
     credentials += Credentials(Path.userHome / ".s3credentials"),
     // Important: always issue "clean" before an S3 upload
     // until here

     publishArtifact in Compile := false,

     publishMavenStyle := false,
     autoScalaLibrary := false,

     mapArt(packageZipTarball, "tgz"),
     mapArt(packageBin, "zip"),

     crossPaths := false

  ) ++
    addArtifact(artifact in (Universal, packageZipTarball), packageZipTarball in Universal) ++
    addArtifact(artifact in (Universal, packageBin), packageBin in Universal)


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
  java-annoying-cla-shtuff: http://download.java.net/maven/2/
  typesafe-releases: http://repo.typesafe.com/typesafe/releases
  typesafe-ivy-releases: http://repo.typesafe.com/typesafe/ivy-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  typesafe-ivy-snapshots: http://repo.typesafe.com/typesafe/ivy-snapshots, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  sbt-plugin-releases: http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
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
