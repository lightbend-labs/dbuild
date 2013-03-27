package distributed
package project
package model

import org.specs2.mutable.Specification
import model._
import Utils.mapper.{writeValueAsString,readValue}

import com.lambdaworks.jacks._
import JacksOption._

object RepeatableDistributedBuildSpec extends Specification {

  "RepeatableDistributedBuild" should {
    
    val sample = RepeatableDistributedBuild(Seq(
          ProjectConfigAndExtracted(
              ProjectBuildConfig("a", "system", "uri", extra = None),
              ExtractedBuildMeta("uri", 
                  Seq(Project(
                    name = "a",
                    organization = "a",
                    artifacts = Seq(ProjectRef("a", "a", classifier = None)),
                    dependencies = Seq.empty)
                  ))),
          ProjectConfigAndExtracted(
              ProjectBuildConfig("b", "system", "uri2", extra = None),
              ExtractedBuildMeta("uri2", 
                  Seq(Project(
                    name = "b",
                    organization = "b",
                    artifacts = Seq(ProjectRef("b", "b", classifier = None)),
                    dependencies = Seq(ProjectRef("a", "a", classifier = None))
                  )))),
         ProjectConfigAndExtracted(
              ProjectBuildConfig("c", "system", "uri3", extra = None),
              ExtractedBuildMeta("uri3", 
                  Seq(Project(
                    name = "c",
                    organization = "c",
                    artifacts = Seq(ProjectRef("c", "c", classifier = None)),
                    dependencies = Seq(ProjectRef("a", "a", classifier = None))
                  )))),
         ProjectConfigAndExtracted(
              ProjectBuildConfig("d", "system", "uri4", extra = None),
              ExtractedBuildMeta("uri4", 
                  Seq(Project(
                    name = "d",
                    organization = "d",
                    artifacts = Seq(ProjectRef("d", "d", classifier = None)),
                    dependencies = Seq(ProjectRef("c", "c", classifier = None), ProjectRef("b", "b", classifier = None))
                  )))),
        ProjectConfigAndExtracted(
              ProjectBuildConfig("e", "system", "uri5", extra = None),
              ExtractedBuildMeta("uri5", 
                  Seq(Project(
                    name = "e",
                    organization = "e",
                    artifacts = Seq(ProjectRef("e", "e", classifier = None)),
                    dependencies = Seq.empty
                  ))))
    ))
    
    val sample2 = RepeatableDistributedBuild(Seq(
          ProjectConfigAndExtracted(
              ProjectBuildConfig("a", "system", "uri", extra = None),
              ExtractedBuildMeta("uri", 
                  Seq(Project(
                    name = "a",
                    organization = "a",
                    artifacts = Seq(ProjectRef("a", "a", classifier = None)),
                    dependencies = Seq.empty)
                  ))),
          ProjectConfigAndExtracted(
              ProjectBuildConfig("b", "system", "uri2", extra = None),
              ExtractedBuildMeta("uri2", 
                  Seq(Project(
                    name = "b",
                    organization = "b",
                    artifacts = Seq(ProjectRef("b", "b", classifier = None)),
                    dependencies = Seq(ProjectRef("a", "a", classifier = None))
                  )))),
         ProjectConfigAndExtracted(
              ProjectBuildConfig("c", "system", "uri3", extra = None),
              ExtractedBuildMeta("uri3", 
                  Seq(Project(
                    name = "c",
                    organization = "c",
                    artifacts = Seq(ProjectRef("c", "c", classifier = None)),
                    dependencies = Seq(ProjectRef("a", "a", classifier = None))
                  ))))
    ))
    
    
    "serialize/deserialize" in {
      val result = readValue[RepeatableDistributedBuild](writeValueAsString(sample))
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