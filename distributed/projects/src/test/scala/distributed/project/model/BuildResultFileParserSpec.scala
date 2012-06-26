package distributed
package project
package model

import org.specs2.mutable.Specification

object BuildArtifactsParserSpec extends Specification {
  "BuildArtifactsParser" should {
    
    "parse pretty printed result" in {
      val data = 
        BuildArtifacts( 
          Seq(
              ArtifactLocation(ProjectDep("p3", "o2"), new java.io.File("p1").getAbsoluteFile, "1.0"),
              ArtifactLocation(ProjectDep("p3", "o2"), new java.io.File("p1").getAbsoluteFile, "2.0")
          ),
          new java.io.File("repo").getAbsoluteFile
        )
      val config = pretty.ConfigPrint(data)
      (BuildArtifactsParser.parseMetaString(config) 
          must 
          equalTo(Some(data)))
    }
  }
}