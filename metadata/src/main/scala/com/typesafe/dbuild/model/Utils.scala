package com.typesafe.dbuild.model

import ClassLoaderMadness.withContextLoader
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config.ConfigFactory.{ parseString, parseFile }
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.Config
import com.lambdaworks.jacks.JacksOption._
import com.lambdaworks.jacks.JacksMapper
import com.fasterxml.jackson.databind.JsonMappingException
import java.io.File

object Utils {
  private val mapper = JacksMapper.withOptions(CaseClassCheckNulls(true),
    CaseClassSkipNulls(true), CaseClassRequireKnown(true))
  def readValueT[T](c: Config)(implicit m: Manifest[T]) =
    withContextLoader(getClass.getClassLoader) {
      val expanded = c.resolve.root.render(ConfigRenderOptions.concise)
      try {
        mapper.readValue[T](expanded)
      } catch {
        case e: JsonMappingException =>
          val m2 = try {
            val margin = 50
            val len = expanded.length()
            val offset = e.getLocation().getCharOffset().toInt
            val (s1, o1) = if (offset > margin) {
              ("..." + expanded.substring(offset - margin + 3), margin)
            } else (expanded, offset)
            val l1 = s1.length()
            val s2 = if (l1 - o1 > margin) {
              s1.substring(0, o1 + margin - 3) + "..."
            } else s1
            val m1 = "\n" + s2 + "\n" + " " * o1 + "^"
            if (e.getMessage().startsWith("Can not deserialize instance of java.lang.String"))
              m1 + "\nA string may have been found in place of an array, somewhere in this object" else m1
          } catch {
            case f:Throwable => throw new JsonMappingException("Internal dbuild exception while deserializing; please report.", e)
          }
          throw new JsonMappingException(e.getMessage.split("\n")(0) + m2, e.getCause)
      }
    }
  def readValue[T](f: File)(implicit m: Manifest[T]) = readValueT[T](parseFile(f))
  def readValue[T](s: String)(implicit m: Manifest[T]) = readValueT[T](parseString(s))
  def writeValue[T](t: T)(implicit m: Manifest[T]) =
    withContextLoader(getClass.getClassLoader) { mapper.writeValueAsString[T](t) }
  def writeValueFormatted[T](t: T)(implicit m: Manifest[T]) =
    com.typesafe.config.ConfigFactory.parseString(writeValue(t)).root.render(ConfigRenderOptions.concise.setFormatted(true))
  def readProperties(f: File) = {
    val config = parseFile(f)
    // do not resolve yet! some needed vars may be in prop files which have not been parsed yet
    // resolve *only* the properties key, in case we are using env vars there
    val rendered = try {
      val value = config.root.withOnlyKey("properties").toConfig().resolve().getValue("properties")
      value.render(ConfigRenderOptions.concise)
    } catch {
      case e: Missing => "[]"
    }
    try {
      mapper.readValue[SeqString](rendered)
    } catch {
      case e:Throwable => throw new JsonMappingException("The \"properties\" section contains unexpected data.", e)
    }
  }

  private val mapper2 = JacksMapper
  // specific simplified variant to deal with reading a path from a /possible/ Artifactory response,
  // as well as a possible response from Flowdock
  def readSomePath[T](s: String)(implicit m: Manifest[T]) =
    withContextLoader(getClass.getClassLoader) {
      try {
        Some(mapper2.readValue[T](s))
      } catch {
        case e: JsonMappingException => None
      }
    }

  // verify whether project/space names are legal
  //
  private val reserved = Seq("standard", "dbuild", "root", ".")
  private val validChars = (('a' to 'z') ++ ('0' to '9') ++ Seq('-', '_')).toSet
  private def testName(name: String, dotsAllowed: Boolean) = {
    val lower = name.toLowerCase
    if (reserved.contains(lower)) {
      sys.error("The names \"dbuild\", \"root\", \"standard\", and \".\" are reserved; please choose a different name: \"" + name + "\".")
    }
    // for projects, "default" is also illegal (but it's legal for spaces)
    if (!dotsAllowed && lower == "default") {
      sys.error("The project name \"default\"is reserved; please choose a different name.")
    }
    if (!(lower forall (c => validChars(c) || (dotsAllowed && c == '.')))) {
      sys.error("Names can only contain letters, numbers, dashes, underscores" + (if (dotsAllowed) ", and dots" else "") + ", found: \"" + name + "\".")
    }
    if (dotsAllowed && (name.startsWith(".") || name.endsWith("."))) {
      sys.error("Names cannot start or end with a dot, found: \"" + name + "\".")
    }
    if (dotsAllowed && name.contains("..")) {
      sys.error("Names cannot contain two consecutive dots, found: \"" + name + "\".")
    }
  }
  def testProjectName(name: String) = testName(name, dotsAllowed = false)
  def testSpaceName(name: String) = testName(name, dotsAllowed = true)

  // spaces-related utilities

  // returns the list of this space + all containing spaces
  def allParents(name: String): Seq[String] = {
    name.split('.').toList match {
      case first :: rest => rest.scanLeft(first)(_ + "." + _)
      case Nil => sys.error("Internal error: subdivided space name was empty")
    }
  }

  // returns the topmost parent of this space
  def topParent(name: String): String = {
    val index = name.indexOf('.')
    if (index < 0) name else name.substring(0, index)
  }

  /**
   * Returns true if a project that requires that its dependencies are
   * visible in the "from" space will be able to find them in one
   * of the spaces listed in the "to" list.
   * Meaning that either "from" or one of its parents is exactly found
   * in the "to" list.
   * if from is "", the project cannot see any other project's dependencies
   */
  def canSeeSpace(from: String, to: Seq[String]) = {
    // like:
    // allParents(from).toSet.intersect(to.toSet).nonEmpty
    // but done using simple string comparisons.
    from != "" && to.exists(t => t == from || from.startsWith(t + "."))
  }

  /**
   * Determine if two spaces are identical, or one is
   * in a space that is hierarchically a parent of the
   * other.
   * The meaning is: return true if, when publishing the
   * same artifacts to both spaces, you get a collision.
   * It actually returns Some(space) if that is where
   * the collision occurs, or None if no collision.
   * if a space is "", then artifacts end in /dev/null
   * and they are never retrieved, so no conflict
   */
  // Conceptually, this is equivalent to:
  //   allParents(one).contains(two) || allParents(two).contains(one)
  // but we can do less work by comparing just the strings.
  def collidingSpaces(one: String, two: String) =
    if (one == "" || two == "")
      None
    else if (one == two || two.startsWith(one + "."))
      Some(two)
    else if (one.startsWith(two + "."))
      Some(one)
    else
      None

  /**
   * Check whether any pair of elements from the first and second
   * sequences, respectively, are colliding.
   * Returns Some(collidingElement), or None if no collision.
   */
  def collidingSeqSpaces(one: Seq[String], two: Seq[String]): Option[String] = {
    // In principle the algorithm could be more efficient,
    // but the sizes of our sequences will normally be absolutely
    // tiny (one or two elements in most cases)
    def traverse[T](seq: Seq[String], item: T, f: (String, T) => Option[String]): Option[String] = seq match {
      case Nil => None
      case first :: rest =>
        f(first, item) match {
          case None => traverse(rest, item, f)
          case some => some
        }
    }
    def collidingSeq(item: String, seq: Seq[String]): Option[String] = traverse(seq, item, collidingSpaces)
    traverse(one, two, collidingSeq)
  }
}
