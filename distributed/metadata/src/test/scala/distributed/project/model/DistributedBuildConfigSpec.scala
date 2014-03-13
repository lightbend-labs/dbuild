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
          setVersion = None,
          setVersionSuffix = None,
          extra = None
      )), None
    ))
    }
    "parse project" in {
      
      
      readValue[DistributedBuildConfig](
"""{
  projects = [{
          name = "p1"
          uri = "uri"
          system = "sbt"
          set-version = "3.9.43"
          extra = { directory = "ZOMG" }
    }]
}""") must equalTo(DistributedBuildConfig(
      Seq(ProjectBuildConfig(
          name = "p1",
          uri = "uri",
          system = "sbt",
          setVersion = Some("3.9.43"),
          setVersionSuffix = None,
          extra = readValue[Option[SbtExtraConfig]]("{directory = ZOMG}")
    		  )), None
    ))
    }
  }
}