package distributed.project.model

import org.specs2.mutable.Specification
import Utils.{writeValue,readValue}

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
      val config = writeValue(data)
      (readValue[BuildArtifacts](config) 
          must 
          equalTo(data))
    }
  }
}