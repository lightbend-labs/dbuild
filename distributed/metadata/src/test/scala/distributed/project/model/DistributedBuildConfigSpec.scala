package distributed
package project
package model

import org.specs2.mutable.Specification
import project.model._
import config._

object DistributedBuildConfigSpec extends Specification {
  "DistributedBuildConfig" should {
    "parse project with defaults" in {
      
      
      parseStringInto[DistributedBuildConfig](
"""{
  projects = [{
          name = "p1"
          uri = "uri"
    }]
}""") must equalTo(Some(DistributedBuildConfig(
      Seq(ProjectBuildConfig(
          name = "p1",
          uri = "uri",
          system = "sbt",
          extra = config.parseString("{}").resolve.root
      ))
    )))
    }
    "parse project" in {
      
      
      parseStringInto[DistributedBuildConfig](
"""{
  projects = [{
          name = "p1"
          uri = "uri"
          system = "humpty"
          extra = { directory = "ZOMG" }
    }]
}""") must equalTo(Some(DistributedBuildConfig(
      Seq(ProjectBuildConfig(
          name = "p1",
          uri = "uri",
          system = "humpty",
          extra = config.parseString("{directory = ZOMG}").resolve.root
      ))
    )))
    }
  }
}