package distributed
package project
package model

import org.specs2.mutable.Specification
import config.makeConfigString

object BuildArtifactsParserSpec extends Specification {
  "BuildArtifactsParser" should {
    
    "parse pretty printed result" in {
      val data = 
        BuildArtifacts( 
          Seq(
              ArtifactLocation(ProjectRef("p3", "o2"), new java.io.File("p1").getAbsoluteFile, "1.0"),
              ArtifactLocation(ProjectRef("p3", "o2"), new java.io.File("p1").getAbsoluteFile, "2.0")
          ),
          new java.io.File("repo").getAbsoluteFile
        )
      val config = makeConfigString(data)
      (BuildArtifactsParser.parseMetaString(config) 
          must 
          equalTo(Some(data)))
    }
  }
}