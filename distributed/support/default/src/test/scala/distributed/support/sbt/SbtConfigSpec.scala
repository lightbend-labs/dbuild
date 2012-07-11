package distributed
package support
package sbt

import org.specs2.mutable.Specification
import config._

object BuildArtifactsSpec extends Specification {
  "BuildArtifactsParser" should {
    
    "parse pretty printed result" in {
      val data = 
        SbtConfig("ZOMG", "dir")
      val config = makeConfigString(data)
      (parseStringInto[SbtConfig](config) 
          must 
          equalTo(Some(data)))
    }
    "parse default result" in {
      val data = 
        SbtConfig(SbtConfig.sbtVersion, "")
      val config = "{}"
      (parseStringInto[SbtConfig](config) 
          must 
          equalTo(Some(data)))
    }
  }
}