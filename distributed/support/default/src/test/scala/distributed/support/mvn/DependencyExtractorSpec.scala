package distributed
package support
package mvn

import project.model._
import org.specs2.mutable.Specification


object DependencyExtractorSpec extends Specification {
  "Maven dependency extraction" should {
    "discover simple dependencies" in {
      val pom = """
<project xmlns="http://maven.apache.org/POM/4.0.0"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd"
        >
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.jsuereth</groupId>
        <artifactId>stuff</artifactId>
        <version>1.0</version>
        <packaging>jar</packaging>
        <name>Stufferz</name>
        <description>
                STUFFS
        </description>
        <url>http://jsuereth.com/stuff</url>
        <inceptionYear>2005</inceptionYear>
        <licenses>
                <license>
                        <name>BSD-style</name>
                        <url>http://scalax.scalaforge.org/license.html</url>
                        <distribution>repo</distribution>
                </license>
        </licenses>
        <dependencies>
          <dependency>
            <groupId>org.scala-lang</groupId>
            <artifactId>scala-library</artifactId>
            <version>2.9.1</version>
           </dependency>
      </dependencies>
 </project>
        """
      
      val meta = DependencyExtractor extract pom
      meta must equalTo(ExtractedBuildMeta("http://jsuereth.com/stuff", "1.0", Seq(
        Project(
          name = "stuff",
          organization = "com.jsuereth",
          artifacts = Seq(ProjectRef("stuff", "com.jsuereth")),
          dependencies = Seq(ProjectRef("scala-library", "org.scala-lang"))
        )
      )))
    }
  }
}