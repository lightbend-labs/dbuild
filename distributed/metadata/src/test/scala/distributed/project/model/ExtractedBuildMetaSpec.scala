package distributed
package project
package model

import org.specs2.mutable.Specification
import model._
import Utils.fromHOCON
import Utils.mapper.{writeValueAsString,readValue}

object ExtractedBuildMetaSpec extends Specification {

  "ExtractedBuildMeta" should {
    "parse metadata file" in {
      
      readValue[ExtractedBuildMeta](fromHOCON(
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
}""")) must equalTo(ExtractedBuildMeta("foo/bar", 
    Seq(Project("p1", "o1", Seq(ProjectRef("p1", "o1", "jar", None)), Seq.empty))))
    }
    
    "parse pretty printed metadata" in {
      val data = 
        ExtractedBuildMeta("foo/bar", 
          Seq(
              Project("p1", "o1", Seq(ProjectRef("p1", "o1", classifier = None)), Seq.empty),
              Project("p2", "o1", Seq(ProjectRef("p2", "o1", classifier = None)), 
                  Seq(
                    ProjectRef("p3", "o2", classifier = None),
                    ProjectRef("p4", "o3", classifier = None)
                  ))))
      val config = writeValueAsString(data)
      (readValue[ExtractedBuildMeta](config) 
          must 
          equalTo(data))
    }
  }
}