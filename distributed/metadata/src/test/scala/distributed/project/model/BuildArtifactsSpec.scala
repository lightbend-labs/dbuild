package distributed.project.model

import org.specs2.mutable.Specification
import Utils.fromHOCON
import Utils.mapper.{writeValueAsString,readValue}

object BuildArtifactsSpec extends Specification {

  "BuildArtifactsParser" should {
    
    "parse pretty printed result" in {
      val data = 
        BuildArtifacts( 
          Seq(
              ArtifactLocation(ProjectRef("p3", "o2", classifier = None), "1.0"),
              ArtifactLocation(ProjectRef("p3", "o2", classifier = None), "2.0")
          ),
          new java.io.File("repo").getAbsoluteFile
        )
      val config = writeValueAsString(data)
      (readValue[BuildArtifacts](config) 
          must 
          equalTo(data))
    }
  }
}