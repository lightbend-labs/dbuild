package distributed
package repo
package core


import project.model._
import java.io.File
import sbt.{RichFile, IO, Path}
import Path._


object ProjectRepoMain {
  def main(args: Array[String]): Unit = {
    val cache = Repository.localCache()
    val projectRepo = new ReadableProjectRepository(cache)
    args.toSeq match {
      case Seq("project", uuid) =>
        // TODO - Pretty print stuff.
        println("--- Project Build: " + uuid)
        val (meta, arts) = projectRepo.getProjectInfo(uuid)
        println("-- Artifacts -- ")
        for(artifact <- meta.versions) {
          println(artifact.dep + ":" + artifact.version + "\t " + artifact.buildTime)
        }
        println("-- Files -- ")
        for((file, sha) <- arts) {
          println(sha.sha + "  " + file.length + "\t" + sha.location)
        }
      case Seq("build", uuid) =>
        println("--- RepetableBuild: " + uuid)
        for {
          build <- LocalRepoHelper.readBuildMeta(uuid, cache)
          project <- build.repeatableBuilds
          name = project.config.name
        } println(" " + project.uuid + " " + name)
      case _ =>  println("TODO - Usage")
    }
  }
}

