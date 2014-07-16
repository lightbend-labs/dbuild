package distributed.project.model

import org.specs2.mutable.Specification
import Utils.{writeValue,readValue}

object BuildArtifactsSpec extends Specification {

  "BuildArtifactsParser" should {
    
    "parse pretty printed result" in {
      val data = 
        BuildArtifactsOut( 
          Seq(BuildSubArtifactsOut("x",Seq(
              ArtifactLocation(ProjectRef("p3", "o2"), "1.0", "", None),
              ArtifactLocation(ProjectRef("p3", "o2"), "2.0", "", None)
          ),Seq.empty, com.typesafe.reactiveplatform.manifest.ModuleInfo("a","b","1.0",
              com.typesafe.reactiveplatform.manifest.CrossBuildProperties(None,None))))
        )
      val config = writeValue(data)
      (readValue[BuildArtifactsOut](config) 
          must 
          equalTo(data))
    }
  }
}