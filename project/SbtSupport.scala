import sbt._
import Keys._
import SyntaxAdapter.syntaxio._
import SyntaxAdapter.{syntaxCompile=>Compile}

object SbtSupport {
  val sbtLaunchJarUrl = SettingKey[String]("sbt-launch-jar-url")
  val sbtLaunchJarLocation = SettingKey[File]("sbt-launch-jar-location")  
  val sbtLaunchJar = TaskKey[Seq[java.io.File]]("sbt-launch-jar", "Resolves SBT launch jar")

  def currentDownloadUrl(v: String) = "http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/"+v+"/sbt-launch.jar"
  def oldDownloadUrl(v: String) = "http://repo.typesafe.com/typesafe/ivy-releases/org.scala-tools.sbt/sbt-launch/"+v+"/sbt-launch.jar"
  def oneDotZeroDownloadUrl(v: String) = "https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/"+v+"/sbt-launch.jar"

  def downloadUrlForVersion(v: String) = (v split "[^\\d]" filter (_ matches "[\\d]+") map (_.toInt)) match {
    case Array(0, 11, x, _*) if x >= 3 => currentDownloadUrl(v)
    case Array(0, y, _*) if y >= 12    => currentDownloadUrl(v)
    case Array(1, _, _*)               => oneDotZeroDownloadUrl(v)
    case _                             => oldDownloadUrl(v)
  }

  def downloadFile(uri: String, file: File): Seq[File] = {
    import dispatch.classic._
    if(!file.exists) {
       // oddly, some places require us to create the file before writing...
       IO.touch(file)
       val writer = new java.io.BufferedOutputStream(new java.io.FileOutputStream(file))
       try Http(url(uri) >>> writer)
       finally writer.close()
    }
    // TODO - GPG Trust validation.
    Seq(file)
  }

  val buildSettings: Seq[Setting[_]] = Seq(
    sbtLaunchJarUrl := downloadUrlForVersion(sbtVersion.value),
    sbtLaunchJarLocation := baseDirectory.value / "target" / "sbt" / "sbt-launch.jar",
    sbtLaunchJar := downloadFile(sbtLaunchJarUrl.value, sbtLaunchJarLocation.value)
  )
  val settings: Seq[Setting[_]] = buildSettings ++ Seq(
    resourceGenerators in Compile += sbtLaunchJar.taskValue
  )
}
