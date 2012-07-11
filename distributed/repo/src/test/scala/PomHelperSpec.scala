package distributed
package repo

import org.specs2.mutable.Specification
import project.model._
import org.specs2.analysis.Dependencies
import java.io.File

object PomHelperSpec extends Specification {
  
  def makeBuildArts: (DistributedBuild, BuildArtifacts) = {
    val build = DistributedBuild(
            Seq(Build(
                config = BuildConfig("", "", "" ),
                extracted = ExtractedBuildMeta(
                  uri = "",
                  projects = Seq(
                    Project(
                      name = "scala-arm",
                      organization = "com.jsuereth",
                      artifacts = Seq(ProjectRef("scala-arm", "com.jsuereth")),
                      dependencies = Seq(ProjectRef("scala-library", "org.scala-lang"))
                    )
                  )
                )
            ))
          )
      val arts = BuildArtifacts(Seq(
        ArtifactLocation(ProjectRef("scala-arm", "com.jsuereth"), new File("."), "1.2")    
      ), new File("."))
      
    (build,arts)
  }
  
  "A PomHelper" should {
    "create pom models" in {
      val (build,arts) = makeBuildArts
      val poms = PomHelper.makePoms(build, arts)
      
      poms must haveSize(1)
      val pom = poms.head
      pom.getArtifactId must equalTo("scala-arm")
      pom.getDependencies.size must equalTo(1)
    }
    "create pom strings" in {
      val (build,arts) = makeBuildArts
      val poms = PomHelper.makePomStrings(build, arts)
      
      poms must haveSize(1)
      val pom = poms.head
      println(pom)
      pom must contain("<artifactId>scala-arm</artifactId>")
      pom must contain("<dependencies>")
      pom must contain("<dependency>")
    }
  }
}