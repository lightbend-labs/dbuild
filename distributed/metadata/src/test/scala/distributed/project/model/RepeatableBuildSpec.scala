package distributed
package project
package model

import org.specs2.mutable.Specification
import model._
import config._

object RepeatableDistributedBuildSpec extends Specification {
  "RepeatableDistributedBuild" should {
    
    val sample = RepeatableDistributedBuild(Seq(
          RepeatableProjectBuild(
              ProjectBuildConfig("a", "system", "uri"),
              ExtractedBuildMeta("uri", 
                  Seq(Project(
                    name = "a",
                    organization = "a",
                    artifacts = Seq(ProjectRef("a", "a")),
                    dependencies = Seq.empty)
                  ))),
          RepeatableProjectBuild(
              ProjectBuildConfig("b", "system", "uri2"),
              ExtractedBuildMeta("uri2", 
                  Seq(Project(
                    name = "b",
                    organization = "b",
                    artifacts = Seq(ProjectRef("b", "b")),
                    dependencies = Seq(ProjectRef("a", "a"))
                  )))),
         RepeatableProjectBuild(
              ProjectBuildConfig("c", "system", "uri3"),
              ExtractedBuildMeta("uri3", 
                  Seq(Project(
                    name = "c",
                    organization = "c",
                    artifacts = Seq(ProjectRef("c", "c")),
                    dependencies = Seq(ProjectRef("a", "a"))
                  )))),
         RepeatableProjectBuild(
              ProjectBuildConfig("d", "system", "uri4"),
              ExtractedBuildMeta("uri4", 
                  Seq(Project(
                    name = "d",
                    organization = "d",
                    artifacts = Seq(ProjectRef("d", "d")),
                    dependencies = Seq(ProjectRef("c", "c"), ProjectRef("b", "b"))
                  )))),
        RepeatableProjectBuild(
              ProjectBuildConfig("e", "system", "uri5"),
              ExtractedBuildMeta("uri5", 
                  Seq(Project(
                    name = "e",
                    organization = "e",
                    artifacts = Seq(ProjectRef("e", "e")),
                    dependencies = Seq.empty
                  ))))
    ))
    
    val sample2 = RepeatableDistributedBuild(Seq(
          RepeatableProjectBuild(
              ProjectBuildConfig("a", "system", "uri"),
              ExtractedBuildMeta("uri", 
                  Seq(Project(
                    name = "a",
                    organization = "a",
                    artifacts = Seq(ProjectRef("a", "a")),
                    dependencies = Seq.empty)
                  ))),
          RepeatableProjectBuild(
              ProjectBuildConfig("b", "system", "uri2"),
              ExtractedBuildMeta("uri2", 
                  Seq(Project(
                    name = "b",
                    organization = "b",
                    artifacts = Seq(ProjectRef("b", "b")),
                    dependencies = Seq(ProjectRef("a", "a"))
                  )))),
         RepeatableProjectBuild(
              ProjectBuildConfig("c", "system", "uri3"),
              ExtractedBuildMeta("uri3", 
                  Seq(Project(
                    name = "c",
                    organization = "c",
                    artifacts = Seq(ProjectRef("c", "c")),
                    dependencies = Seq(ProjectRef("a", "a"))
                  ))))
    ))
    
    
    "serialize/deserialize" in {
      val result = parseStringInto[RepeatableDistributedBuild](makeConfigString(sample)) getOrElse sys.error("Failure to parse")
      result must equalTo(sample)
    }
    "Make unique project build UUID" in {
      sample.projectUUID("a").isEmpty must beFalse
      sample.projectUUID("b").isEmpty must beFalse
      sample.projectUUID("c").isEmpty must beFalse
      sample.projectUUID("d").isEmpty must beFalse
      sample.projectUUID("e").isEmpty must beFalse
    }
    "Must make repeatable UUIDs" in {
      val duplicate = parseStringInto[RepeatableDistributedBuild](makeConfigString(sample)) getOrElse sys.error("Failure to parse")
      sample.projectUUID("a") == duplicate.projectUUID("a") must beTrue
      sample.projectUUID("b") == duplicate.projectUUID("b") must beTrue
      sample.projectUUID("c") == duplicate.projectUUID("c") must beTrue
      sample.projectUUID("d") == duplicate.projectUUID("d") must beTrue
      sample.projectUUID("e") == duplicate.projectUUID("e") must beTrue
      sample.projectUUID("a") == sample2.projectUUID("a") must beTrue
      sample.projectUUID("b") == sample2.projectUUID("b") must beTrue
      sample.projectUUID("c") == sample2.projectUUID("c") must beTrue
    }
    
    "Must make build UUIDs" in {
      sample.uuid must not(equalTo(sample2.uuid))
      sample.uuid must not(equalTo(hashing sha1Sum ""))
    }
    
  }
}