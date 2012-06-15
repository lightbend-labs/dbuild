package distributed
package support
package scala

import project.model._
import project.dependencies.BuildDependencyExtractor
import _root_.java.io.File

object ScalaDependencyExtractor extends BuildDependencyExtractor {
  override def extract(config: BuildConfig, dir: File, log: logging.Logger): ExtractedBuildMeta = {
    // TODO - don't HardCode
    ExtractedBuildMeta("", 
        Seq(
          Project("jline", group, Seq.empty),
          Project("scala-library", group, Seq.empty),
          Project("scala-reflection", group, Seq(lib)),
          Project("scala-actors", group, Seq(lib)),
          Project("scala-actors-migration", group, Seq(lib, actors)),
          Project("scala-swing", group, Seq(lib)),
          Project("scala-compiler", group, Seq(reflect, jline)),
          Project("scalap", group, Seq(comp)),
          Project("partest", group, Seq(comp, actors))
        ))
  }
  def canHandle(system: String): Boolean = "scala" == system
  
  private[this] def group = "org.scala-lang"
  private[this] def lib = ProjectDep("scala-library", group)
  private[this] def reflect = ProjectDep("scala-reflect", group)
  private[this] def actors = ProjectDep("scala-actors", group)
  private[this] def jline = ProjectDep("jline", group)
  private[this] def comp = ProjectDep("scala-compiler", group)
}

