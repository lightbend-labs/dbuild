package com.typesafe.dbuild.support

/**
 * Utility to detect/create scala binary version extensions.
 */
object CrossVersion {

  /**
   * Figures out what artifact extension is required.
   *
   * @param crossConfiguration
   *          The dbuild configuration string.  One of
   *          - 'binary'
   *          - 'disabled'
   *          - 'full'
   *          - 'standard'
   * @param scalaFullVersion
   *           The full version of scala (no attempts to create a binary version from it).
   * @return
   *           The extension to an artifact name which denotes the scala compatibility of that artifact.
   */
  def artifactNameExtension(crossConfiguration: String, scalaFullVersion: String): String =
    crossConfiguration match {
      case "disabled" => ""
      case "full" => "_" + scalaFullVersion
      case "binary" => "_" + binaryVersion(scalaFullVersion)
      case "standard" => standardCrossVersionSuffix(scalaFullVersion)
      case cv => sys.error("Fatal: unrecognized cross-version option \"" + cv + "\"")
    }





  private val ReleaseV = """(\d+)\.(\d+)\.(\d+)(-\d+)?""".r
  private val BinCompatV = """(\d+)\.(\d+)\.(\d+)-bin(-.*)?""".r
  private val NonReleaseV = """(\d+)\.(\d+)\.(\d+)(-\w+)""".r
  /** Returns the "standard" cross version suffix for a Scala version.
    *
    * TODO - This should MATCH sbt's decision process, possibly by being the same.
    *        We can't exactly use sbt's logic until we can drop Scala 2.9 + sbt 0.12 support.
    */
  def standardCrossVersionSuffix(v: String): String = {
    val bcSuffix: String = v match {
      case ReleaseV(x, y, z, ht) => x + "." + y
      case BinCompatV(x, y, z, ht) => x + "." + y
      case NonReleaseV(x, y, z, ht) if z.toInt > 0 => x + "." + y
      case _ => v
    }
    "_" + bcSuffix
  }

  /** Returns true if the scala version is binary compatible. */
  def isBinaryCompatibleScalaVersion(version: String): Boolean = version.contains('-')



  private val BinaryPart = """(\d+\.\d+)(?:\..+)?""".r
  /**
   * @param s The original version string.
   * @return  The binary version string.
   */
  def binaryVersion(s: String) = s match {
    case BinaryPart(z) => z
    case _ => sys.error("Fatal: cannot extract Scala binary version from string \"" + s + "\"")
  }
}
