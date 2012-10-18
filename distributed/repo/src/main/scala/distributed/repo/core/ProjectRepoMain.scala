package distributed
package repo
package core


import project.model._
import java.io.File
import sbt.{RichFile, IO, Path}
import Path._


object ProjectRepoMain {
  def main(args: Array[String]): Unit = {
    val repo = new ReadableProjectRepository(Repository.localCache())
    args.toSeq match {
      case Seq("read", uuid) =>
        // TODO - Pretty print stuff.
        println("--- Project Build: " + uuid)
        val (meta, arts) = repo.getProjectInfo(uuid)
        println("-- Artifacts -- ")
        for(artifact <- meta.versions) {
          println(artifact.dep + ":" + artifact.version + "\t " + artifact.buildTime)
        }
        println("-- Files -- ")
        for((file, sha) <- arts) {
          println(sha.sha + "  " + file.length + "\t" + sha.location)
        }
      case _ =>  println("TODO - Usage")
    }
  }
}

