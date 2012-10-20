package distributed
package repo
package core


import project.model._
import java.io.File
import sbt.{RichFile, IO, Path}
import Path._

class SbtRepoMain extends xsbti.AppMain {
  def run(configuration: xsbti.AppConfiguration) =
    try {
      val args = configuration.arguments
      ProjectRepoMain.main(args.toArray)
      Exit(0)
    } catch {
      case e: Exception =>
        e.printStackTrace
        Exit(1)
    }
  case class Exit(val code: Int) extends xsbti.Exit
}


object ProjectRepoMain {
  val cache = Repository.localCache()
  val projectRepo = new ReadableProjectRepository(cache)
  
  def main(args: Array[String]): Unit = {
    args.toSeq match {
      case Seq("project", uuid) => printProjectInfo(uuid)
      case Seq("project-files", uuid) =>
        printProjectHeader(uuid)
        printProjectFiles(uuid)
      case Seq("project-artifacts", uuid) =>
        printProjectHeader(uuid)
        printProjectArtifacts(uuid)
      case Seq("build", uuid) => printBuildInfo(uuid)
      case Seq("build-projects", uuid) => printAllProjectInfo(uuid)
      case _ =>  
        println("""|Usage: <repo-reader> <cmd>
                   |  where cmd in:
                   |  -  project <uuid>
                   |      prints the information about a project.
                   |  -  project-files <uuid>
                   |      prints the files stored for a project.
                   |  -  project-artifacts <uuid>
                   |      prints the artifacts published by a project.
                   |  -  build <uuid>
                   |      prints the information about a build.
                   |  -  build-projects <uuid>
                   |      prints the information about projects within a build.
                   |""".stripMargin)
    }
  }
  
  private def printProjectHeader(uuid: String): Unit =
    println("--- Project Build: " + uuid)
    
  def printProjectInfo(uuid: String): Unit = {
        printProjectHeader(uuid)
        printProjectDependencies(uuid)
        printProjectArtifacts(uuid)
        printProjectFiles(uuid)
  }
  
  def printProjectDependencies(uuid:String): Unit = {
    val (meta, _) = projectRepo.getProjectInfo(uuid)
    println(" -- Dependencies --")
    for(uuid <- meta.project.transitiveDependencyUUIDs)
      println("    * " + uuid)
  }
  
  def printProjectArtifacts(uuid:String): Unit = {
    val (meta, _) = projectRepo.getProjectInfo(uuid)
    println(" -- Artifacts -- ")
    val arts = meta.versions
    val groups = padStrings(arts map (_.dep.organization))
    val names = padStrings(arts map (_.dep.name))
    val classifiers = padStrings(arts map (_.dep.classifier getOrElse ""))
    val extensions = padStrings(arts map (_.dep.extension))
    val versions = padStrings(arts map (_.version))
    for {
      ((((group, name), classifier), extension), version) <- groups zip names zip classifiers zip extensions zip versions
    } println("  - " + group + " : " + name + " : " + classifier + " : " + extension + " : " + version)
  }
  
  def padStrings(strings: Seq[String]): Seq[String] = {
    val max = (strings map (_.length)).max
    val pad = Seq.fill(max)(' ') mkString ""
    for {
      string <- strings
      myPad = pad take (max - string.length)
    } yield myPad + string
  }
  
  def printProjectFiles(uuid: String): Unit = {
    val (_, arts) = projectRepo.getProjectInfo(uuid)
    println(" -- Files -- ")
    
    val groups = arts groupBy { x => x._2.location.take(x._2.location.lastIndexOf('/')) }
    for ((dir, arts) <- groups.toSeq.sortBy(_._1)) {
      println("  " + dir)
      printArtifactSeq(arts, true, "    ")
    }
  }
  
  def printArtifactSeq(arts: Seq[(File, ArtifactSha)], shrinkLocation: Boolean = false, pad: String = ""): Unit = {
    val sizes = padStrings(arts map (_._1.length) map prettyFileLength)
    val shas = arts map (_._2.sha)
    val locations = {
      val tmp = arts map (_._2.location)
      if(shrinkLocation) tmp map (x => x.drop(x.lastIndexOf('/')+1))
      else tmp
    }
    for(((size, sha), location) <- sizes zip shas zip locations) {
      println(pad + sha + "  " + size + "  " + location)
    }
  }
  
  def prettyFileLength(length: Long) = length match {
    case x if x > (1024 * 1024 * 1023) => "%3.1fG" format (x.toDouble / (1024.0*1024*1024))
    case x if x > (1024 * 1024) => "%3.1fM" format (x.toDouble / (1024.0*1024))
    case x if x > 1024 => "%3.1fk" format (x.toDouble / 1024.0)
    case x => "%4d" format (x)
  }
  
  def printBuildInfo(uuid: String): Unit = {
        println("--- RepetableBuild: " + uuid)
        println(" = Projects = ")
        for {
          build <- LocalRepoHelper.readBuildMeta(uuid, cache)
          project <- build.repeatableBuilds
          name = project.config.name
        } println("  - " + project.uuid + " " + name)
        println(" = Repeatable Config =")
        LocalRepoHelper.readBuildMeta(uuid, cache) foreach { build =>
           println(config makeConfigString build.repeatableBuildConfig)
        }    
  }
  
  
  def printAllProjectInfo(buildUUID: String): Unit = {
    println("--- RepetableBuild: " + buildUUID)
    for {
      build <- LocalRepoHelper.readBuildMeta(buildUUID, cache)
      project <- build.repeatableBuilds
    } try printProjectInfo(project.uuid)
      catch { case _ => println("     " + project.config.name + " is not built.")}
  }
}

