package distributed.project.model

import org.specs2.mutable.Specification
import Utils.{writeValue,readValue}

object BuildArtifactsSpec extends Specification {

  "BuildArtifactsParser" should {
    
    "parse pretty printed result" in {
      val data = 
        BuildArtifactsOut( 
          Seq(("x",Seq(
              ArtifactLocation(ProjectRef("p3", "o2"), "1.0"),
              ArtifactLocation(ProjectRef("p3", "o2"), "2.0")
          ),Seq("a/b.txt")))
        )
      val config = writeValue(data)
      (readValue[BuildArtifactsOut](config) 
          must 
          equalTo(data))
    }
  }
}