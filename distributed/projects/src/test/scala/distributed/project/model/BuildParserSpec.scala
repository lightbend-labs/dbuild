package distributed
package project
package model

import org.specs2.mutable.Specification
import project.model._

object BuildParserSpec extends Specification {
  "DistributedBuildParser" should {
    "parse project with defaults" in {
      
      
      DistributedBuildParser.parseBuildString(
"""{
  projects = [{
          name = "p1"
          uri = "uri"
    }]
}""") must equalTo(DistributedBuildConfig(
      Seq(BuildConfig(
          name = "p1",
          uri = "uri",
          system = "sbt",
          directory = ""
      ))
    ))
    }
    "parse project" in {
      
      
      DistributedBuildParser.parseBuildString(
"""{
  projects = [{
          name = "p1"
          uri = "uri"
          system = "humpty"
          directory = "ZOMG"
    }]
}""") must equalTo(DistributedBuildConfig(
      Seq(BuildConfig(
          name = "p1",
          uri = "uri",
          system = "humpty",
          directory = "ZOMG"
      ))
    ))
    }
  }
}