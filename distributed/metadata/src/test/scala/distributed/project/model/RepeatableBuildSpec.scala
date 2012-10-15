package distributed
package project
package model

import org.specs2.mutable.Specification
import model._
import config._

object RepeatableDistributedBuildSpec extends Specification {
  "RepeatableDistributedBuild" should {
    
    val sample = RepeatableDistributedBuild(Seq(
          ProjectConfigAndExtracted(
              ProjectBuildConfig("a", "system", "uri"),
              ExtractedBuildMeta("uri", 
                  Seq(Project(
                    name = "a",
                    organization = "a",
                    artifacts = Seq(ProjectRef("a", "a")),
                    dependencies = Seq.empty)
                  ))),
          ProjectConfigAndExtracted(
              ProjectBuildConfig("b", "system", "uri2"),
              ExtractedBuildMeta("uri2", 
                  Seq(Project(
                    name = "b",
                    organization = "b",
                    artifacts = Seq(ProjectRef("b", "b")),
                    dependencies = Seq(ProjectRef("a", "a"))
                  )))),
         ProjectConfigAndExtracted(
              ProjectBuildConfig("c", "system", "uri3"),
              ExtractedBuildMeta("uri3", 
                  Seq(Project(
                    name = "c",
                    organization = "c",
                    artifacts = Seq(ProjectRef("c", "c")),
                    dependencies = Seq(ProjectRef("a", "a"))
                  )))),
         ProjectConfigAndExtracted(
              ProjectBuildConfig("d", "system", "uri4"),
              ExtractedBuildMeta("uri4", 
                  Seq(Project(
                    name = "d",
                    organization = "d",
                    artifacts = Seq(ProjectRef("d", "d")),
                    dependencies = Seq(ProjectRef("c", "c"), ProjectRef("b", "b"))
                  )))),
        ProjectConfigAndExtracted(
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
          ProjectConfigAndExtracted(
              ProjectBuildConfig("a", "system", "uri"),
              ExtractedBuildMeta("uri", 
                  Seq(Project(
                    name = "a",
                    organization = "a",
                    artifacts = Seq(ProjectRef("a", "a")),
                    dependencies = Seq.empty)
                  ))),
          ProjectConfigAndExtracted(
              ProjectBuildConfig("b", "system", "uri2"),
              ExtractedBuildMeta("uri2", 
                  Seq(Project(
                    name = "b",
                    organization = "b",
                    artifacts = Seq(ProjectRef("b", "b")),
                    dependencies = Seq(ProjectRef("a", "a"))
                  )))),
         ProjectConfigAndExtracted(
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
      val uuids: Set[String] = (sample.repeatableBuilds map (_.uuid))(collection.breakOut)
      uuids.size must equalTo(5)
    }
    "Must make repeatable UUIDs" in {
      val uuids: Set[String] = (sample.repeatableBuilds map (_.uuid))(collection.breakOut)
      val uuids2: Set[String] = (sample2.repeatableBuilds map (_.uuid))(collection.breakOut)
      val union = uuids & uuids2
      union.size must equalTo(3)
    }
    
    "Must make build UUIDs" in {
      sample.uuid must not(equalTo(sample2.uuid))
      sample.uuid must not(equalTo(hashing sha1 ""))
    }
    
    "Must have transitive dependencies" in {
      val d = sample.repeatableBuilds find (_.config.name == "d") getOrElse sys.error("Could not find repeatable build for d")
      (d.dependencies find (_.config.name == "b")) must equalTo(sample.repeatableBuilds find (_.config.name == "b"))
      d.transitiveDependencyUUIDs must contain(sample.repeatableBuilds find (_.config.name == "a") map (_.uuid) getOrElse sys.error("could not find repeatable build for a"))
      d.transitiveDependencyUUIDs must not(contain(d.uuid))
    }
    
  }
}