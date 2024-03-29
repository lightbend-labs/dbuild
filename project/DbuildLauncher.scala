import sbt._

object DbuildLauncher {
  val launcherVersion = "1.0.1-d1"
  val launchInt = "com.typesafe.dbuild" % "launcher-interface" % launcherVersion
  val launcher  = "com.typesafe.dbuild" % "launcher" % launcherVersion
  val uri = "https://dl.cloudsmith.io/public/lightbend/maven-releases/maven/com/typesafe/dbuild/launcher/" + launcherVersion +
            "/launcher-" + launcherVersion+".jar"
}

