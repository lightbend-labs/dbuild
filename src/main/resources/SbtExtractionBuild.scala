import sbt._
import Keys._

object Build extends PrintDepsStartup {
  override def settings = super.settings ++ printSettings
  val root = Project("root", file(".")) settings(organization := "removeme") dependsOn(remote)
  lazy val remote = {
    import scala.util.control.Exception.catching
    val potential = for {
       loc <- Option(System.getProperty("remote.project.uri")) 
       //TODO - Better conversion here
       uri <- catching(classOf[java.net.URISyntaxException]) opt (new java.net.URI(loc))
    } yield RootProject(uri)
    potential getOrElse sys.error("Could not find projet uri!")
  }
}

trait PrettyPrint[T] {
  def apply(t: T): String
}

object PrettyPrint {
  def apply[T](t: T)(implicit ev: PrettyPrint[T]) = ev(t)

  val ScalaVersioned = new util.matching.Regex("(.+)_((\\d+)\\.(\\d+)(.+))")
  def fixName(name: String): String = name match {
    case ScalaVersioned(name, _*) => name
    case name => name
  }

  implicit object ModuleIdPretty extends PrettyPrint[ModuleID] {

    def apply(t: ModuleID): String = {
      import t._
      val sb = new StringBuilder("  {\n")
      sb append ("    name = %s\n" format (fixName(name)))
      sb append ("    organization = %s\n" format (organization))
      sb append ("  }\n")
      sb.toString
    }
  }

  implicit object MyDependencyInfoPretty extends PrettyPrint[MyDependencyInfo] {
    def apply(t: MyDependencyInfo): String = {
      import t._
      val sb = new StringBuilder("{\n")
      sb append ("  name = %s\n" format (fixName(name)))
      sb append ("  organization = %s\n" format (organization))
      sb append ("  version = \"%s\"\n" format (version))
      //sb append ("  module: %s\n" format (module))
      sb append ("  dependencies = %s\n" format (PrettyPrint(dependencies)))
      sb append ("}")
      sb.toString
    }
  }

  implicit def seqPretty[T : PrettyPrint]: PrettyPrint[Seq[T]] = new PrettyPrint[Seq[T]] {
    def apply(t: Seq[T]): String = 
      (t map { i => PrettyPrint(i) }).mkString("[", ",\n","]")
  }

  implicit object SbtBuildMetaDataPretty extends PrettyPrint[SbtBuildMetaData] {
    def apply(b: SbtBuildMetaData): String = {
      import b._
      val sb = new StringBuilder("{\n")
      sb append ("scm      = \"%s\"\n" format(scmUri))
      sb append ("projects = %s\n" format (PrettyPrint(projects)))
      sb append ("}")
      sb.toString
    }
  }
}
case class SbtBuildMetaData(projects: Seq[MyDependencyInfo],
                            scmUri: String)
case class MyDependencyInfo(project: ProjectRef,
                            name: String, 
                            organization: String, 
                            version: String, 
                            module: ModuleID,
                            dependencies: Seq[ModuleID] = Seq()) {}

/** A trait for use in builds that can  local dependencies to be local. */
trait DependencyAnalysis {
  /** Hashes a project dependency to just contain organization and name. */
  private def hashInfo(d: MyDependencyInfo) = d.organization + ":" + d.name
  /** Hashes a module ID to just contain organization and name. */
  private def hashModule(o: ModuleID) = o.organization + ":" + o.name
  /** Pulls the name/organization/version for each project in the CEL build */
  private def getProjectInfos(extracted: Extracted, state: State, refs: Iterable[ProjectRef]): Seq[MyDependencyInfo] =
    (Vector[MyDependencyInfo]() /: refs) { (dependencies, ref) =>
      dependencies :+ MyDependencyInfo(
        ref,
        extracted.get(Keys.name in ref),
        extracted.get(Keys.organization in ref),
        extracted.get(Keys.version in ref),
        extracted.get(Keys.projectID in ref),
        extracted.get(Keys.libraryDependencies in ref) ++ extracted.evalTask(Keys.projectDependencies in ref, state))
    }

  def printDependencies(state: State): State = {
    val extracted = Project.extract(state)
    import extracted._
    val refs = (session.mergeSettings map (_.key.scope) collect {
      case Scope(Select(p @ ProjectRef(_,_)),_,_,_) => p
    } toSet)
    val deps = getProjectInfos(extracted, state, refs)
    for {
      file <- Option(System.getProperty("project.dependency.metadata.file"))
      output = new java.io.PrintStream(new java.io.FileOutputStream(file))
    } try output println PrettyPrint(SbtBuildMetaData(deps filterNot (_.organization == "removeme"), System.getProperty("remote.project.uri")))
      finally output.close()

    state
  }
}


/** This trait provides settings to rewire remote dependencies to be local if remote project source is used. */
trait PrintDepsStartup extends Build with DependencyAnalysis {

  private def print = Command.command("print-deps")(printDependencies)

  final def printSettings: Seq[Setting[_]] = Seq(
    commands += print
  )
}