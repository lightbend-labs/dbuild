package distributed
package project
package model

import Utils._
import org.specs2.mutable.Specification
import project.model._
import Utils.{writeValue,readValue}

object DistributedBuildConfigSpec extends Specification {

  "DistributedBuildConfig" should {
    "parse project with defaults" in {

      readValue[DistributedBuildConfig]("""{
  projects = [{
          name = "p1"
          uri = "uri"
    }]
}""") must equalTo(DistributedBuildConfig(
      Seq(ProjectBuildConfig(
          name = "p1",
          uri = "uri",
          system = "sbt",
          extra = None
      ))
    ))
    }
    "parse project" in {
      
      
      readValue[DistributedBuildConfig](
"""{
  projects = [{
          name = "p1"
          uri = "uri"
          system = "humpty"
          extra = { directory = "ZOMG" }
    }]
}""") must equalTo(DistributedBuildConfig(
      Seq(ProjectBuildConfig(
          name = "p1",
          uri = "uri",
          system = "humpty",
          extra = readValue[Option[ExtraConfig]]("{directory = ZOMG}")
      ))
    ))
    }
  }
}