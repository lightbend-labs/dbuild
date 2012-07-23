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

}
