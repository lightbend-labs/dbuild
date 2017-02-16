import sbt._
object BintrayDep212 {
  lazy val bintrayProject = sbt.RootProject(uri("git://github.com/cunei/bintray-sbt#wip-sbt-1.0.0-20170213-160730"))
  val bintrayIf212 = Seq[sbt.util.Eval[sbt.ClasspathDep[sbt.ProjectReference]]](bintrayProject)
}

