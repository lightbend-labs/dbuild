import sbt._
object BintrayDep212 {
  lazy val bintrayProject = sbt.RootProject(uri("git://github.com/cunei/bintray-sbt#topic/sbt1.0.0-M4"))
  val bintrayIf212 = Seq[sbt.util.Eval[sbt.ClasspathDep[sbt.ProjectReference]]](bintrayProject)
}

