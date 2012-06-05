package distributed
package project
package dependencies

import org.specs2.mutable.Specification
import model._

object ParserSpec extends Specification {
  "meta.Parser" should {
    "parse metadata file" in {
      
      
      ExtractedDependencyFileParser.parseMetaString(
"""{
  scm = "foo/bar"  
  projects = [{
          name = "p1"
          organization = "o1"
          dependencies = []
    }]
}""") must equalTo(Some(ExtractedBuildMeta("foo/bar", Seq(Project("p1", "o1", Seq.empty)))))
    }
  }
}