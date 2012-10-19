package distributed
package project
package model

import org.specs2.mutable.Specification
import config._

object BuildArtifactsSpec extends Specification {
  "BuildArtifactsParser" should {
    
    "parse pretty printed result" in {
      val data = 
        BuildArtifacts( 
          Seq(
              ArtifactLocation(ProjectRef("p3", "o2"), "1.0"),
              ArtifactLocation(ProjectRef("p3", "o2"), "2.0")
          ),
          new java.io.File("repo").getAbsoluteFile
        )
      val config = makeConfigString(data)
      (parseStringInto[BuildArtifacts](config) 
          must 
          equalTo(Some(data)))
    }
  }
}