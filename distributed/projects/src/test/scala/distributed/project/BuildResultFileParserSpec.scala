package distributed
package project

import org.specs2.mutable.Specification
import model._

object ParserSpec extends Specification {
  "BuildResultFileParser" should {
    
    "parse pretty printed result" in {
      val data = 
        BuildArtifacts( 
          Seq(
              ArtifactLocation(ProjectDep("p3", "o2"), new java.io.File("p1").getAbsoluteFile),
              ArtifactLocation(ProjectDep("p3", "o2"), new java.io.File("p1").getAbsoluteFile)
          )
        )
      val config = pretty.PrettyPrint(data)
      (BuildResultFileParser.parseMetaString(config) 
          must 
          equalTo(Some(data)))
    }
  }
}