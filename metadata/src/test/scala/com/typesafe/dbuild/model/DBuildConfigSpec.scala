package com.typesafe.dbuild.model

import Utils._
import org.specs2.mutable.Specification
import com.typesafe.dbuild.model._
import Utils.{writeValue,readValue}

object DBuildConfigSpec extends Specification {
  "DBuildConfig" should {
    "parse project with defaults" in {

      readValue[DBuildConfig]("""{
  projects = [{
          name = "p1"
          uri = "uri"
    }]
}""") must equalTo(DBuildConfig(
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
      
      
      readValue[DBuildConfig](
"""{
  projects = [{
          name = "p1"
          uri = "uri"
          system = "sbt"
          set-version = "3.9.43"
          extra = { directory = "ZOMG" }
    }]
}""") must equalTo(DBuildConfig(
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