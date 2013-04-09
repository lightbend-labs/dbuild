package distributed
package project
package model

import org.specs2.mutable.Specification
import model._
import Utils.{writeValue,readValue}

object ExtractedBuildMetaSpec extends Specification {

  "ExtractedBuildMeta" should {
    "parse metadata file" in {
      
      readValue[ExtractedBuildMeta](
"""{
  uri = "foo/bar"  
  projects = [{
          name = "p1"
          organization = "o1"
          artifacts = [{
            name = "p1"
            organization = "o1"
            ext = "jar"
          }]
          dependencies = []
    }]
}""") must equalTo(ExtractedBuildMeta("foo/bar", 
    Seq(Project("p1", "o1", Seq(ProjectRef("p1", "o1", "jar")), Seq.empty))))
    }
    
    "parse pretty printed metadata" in {
      val data = 
        ExtractedBuildMeta("foo/bar", 
          Seq(
              Project("p1", "o1", Seq(ProjectRef("p1", "o1")), Seq.empty),
              Project("p2", "o1", Seq(ProjectRef("p2", "o1")), 
                  Seq(
                    ProjectRef("p3", "o2"),
                    ProjectRef("p4", "o3")
                  ))))
      val config = writeValue(data)
      (readValue[ExtractedBuildMeta](config) 
          must 
          equalTo(data))
    }
  }
}