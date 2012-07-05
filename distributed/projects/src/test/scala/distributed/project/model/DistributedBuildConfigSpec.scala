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
      Seq(BuildConfig(
          name = "p1",
          uri = "uri",
          system = "sbt",
          directory = ""
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
          directory = "ZOMG"
    }]
}""") must equalTo(Some(DistributedBuildConfig(
      Seq(BuildConfig(
          name = "p1",
          uri = "uri",
          system = "humpty",
          directory = "ZOMG"
      ))
    )))
    }
  }
}