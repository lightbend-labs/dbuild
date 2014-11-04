package com.typesafe.dbuild.support

import org.specs2.mutable.Specification
import CrossVersion.{artifactNameExtension, binaryVersion}

class CrossVersionSpec extends Specification {
  "CrossVersion" should {

    "Handle release versions" in {
      val scalaVersion = "2.11.5"
      val expectedBinaryVersion = "2.11"
      binaryVersion(scalaVersion) must beEqualTo(expectedBinaryVersion)
      artifactNameExtension("standard", scalaVersion) must beEqualTo("_" + expectedBinaryVersion)
      artifactNameExtension("binary", scalaVersion) must beEqualTo("_" + expectedBinaryVersion)
      artifactNameExtension("disabled", scalaVersion) must beEqualTo("")
      artifactNameExtension("full", scalaVersion) must beEqualTo("_" + scalaVersion)
    }
    "Handle bin denoted compatible versions" in {
      val scalaVersion = "2.11.5-bin-my-company-release-123"
      val expectedBinaryVersion = "2.11"
      binaryVersion(scalaVersion) must beEqualTo(expectedBinaryVersion)
      artifactNameExtension("standard", scalaVersion) must beEqualTo("_" + expectedBinaryVersion)
      artifactNameExtension("binary", scalaVersion) must beEqualTo("_" + expectedBinaryVersion)
      artifactNameExtension("disabled", scalaVersion) must beEqualTo("")
      artifactNameExtension("full", scalaVersion) must beEqualTo("_" + scalaVersion)
    }
    "Handle snapshot compatible versions" in {
      val scalaVersion = "2.11.5-SNAPSHOT"
      val expectedBinaryVersion = "2.11"
      binaryVersion(scalaVersion) must beEqualTo(expectedBinaryVersion)
      artifactNameExtension("standard", scalaVersion) must beEqualTo("_" + expectedBinaryVersion)
      artifactNameExtension("binary", scalaVersion) must beEqualTo("_" + expectedBinaryVersion)
      artifactNameExtension("disabled", scalaVersion) must beEqualTo("")
      artifactNameExtension("full", scalaVersion) must beEqualTo("_" + scalaVersion)
    }

    "Handle snapshot incompatible versions" in {
      val scalaVersion = "2.11.0-SNAPSHOT"
      val expectedBinaryVersion = "2.11"
      binaryVersion(scalaVersion) must beEqualTo(expectedBinaryVersion)
      artifactNameExtension("standard", scalaVersion) must beEqualTo("_" + scalaVersion)
      // TODO - This may not be correct.....
      artifactNameExtension("binary", scalaVersion) must beEqualTo("_" + expectedBinaryVersion)
      artifactNameExtension("disabled", scalaVersion) must beEqualTo("")
      artifactNameExtension("full", scalaVersion) must beEqualTo("_" + scalaVersion)
    }

  }

}
