package distributed
package project
package dependencies

import org.specs2.mutable.Specification
import model._

object ExtractedDependencyFileParserSpec extends Specification {
  "ExtractedDependencyFileParser" should {
    "parse metadata file" in {
      
      
      ExtractedDependencyFileParser.parseMetaString(
"""{
  scm = "foo/bar"  
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
}""") must equalTo(Some(ExtractedBuildMeta("foo/bar", 
    Seq(Project("p1", "o1", Seq(ProjectDep("p1", "o1", "jar")), Seq.empty)))))
    }
    
    "parse pretty printed metadata" in {
      val data = 
        ExtractedBuildMeta("foo/bar", 
          Seq(
              Project("p1", "o1", Seq(ProjectDep("p1", "o1")), Seq.empty),
              Project("p2", "o1", Seq(ProjectDep("p2", "o1")), 
                  Seq(
                    ProjectDep("p3", "o2"),
                    ProjectDep("p4", "o3")
                  ))))
      val config = pretty.ConfigPrint(data)
      (ExtractedDependencyFileParser.parseMetaString(config) 
          must 
          equalTo(Option(data)))
    }
  }
}