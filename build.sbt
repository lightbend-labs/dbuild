def Proj(name: String) = (
  Project(name, file(if (name=="root") "." else name))
  configs( IntegrationTest )
  settings( Defaults.itSettings : _*)
  settings(
    version := MyVersion,
    organization := "com.typesafe.dbuild",
    selectScalaVersion,
    libraryDependencies += specs2,
    resolvers += Resolver.typesafeIvyRepo("releases"),
    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    publishMavenStyle := false,
    licenses += ("Apache 2", url("http://www.apache.org/licenses/LICENSE-2.0")),
    licenses += ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0")),
    bintrayReleaseOnPublish := false,
    bintrayOrganization := Some("typesafe"),
    bintrayRepository := "ivy-releases",
    bintrayPackage := "dbuild"
  )
)


// DSL for adding remote deps like local deps.
implicit def p2remote(p: Project): RemoteDepHelper = new RemoteDepHelper(p)
class RemoteDepHelper(p: Project) {
  def dependsOnRemote(ms: ModuleID*): Project = p.settings(libraryDependencies ++= ms)
  def dependsOnSbt(ms: (String=>ModuleID)*): Project = p.settings(libraryDependencies <++= (scalaVersion) {sv => ms map {_(sbtVer(sv))}})
  def dependsOnAkka(): Project = p.settings(libraryDependencies <+= (scalaVersion) {sv => if (sv.startsWith("2.9")) akkaActor29 else akkaActor210})
}

SbtSupport.buildSettings
