package distributed
package build

import java.io.File
import distributed.project.model.DBuildConfiguration
import distributed.project.model.Utils.{ writeValue, readValue, readProperties }
import distributed.project.model.ClassLoaderMadness
import distributed.project.model.{ BuildOutcome, BuildBad }
import distributed.project.model.TemplateFormatter
import java.util.Properties
import com.typesafe.config.ConfigFactory
import distributed.project.model.Utils.readValueT
import distributed.utils.Time.timed
import collection.immutable.SortedMap

/** An Sbt buiild runner. */
class SbtBuildMain extends xsbti.AppMain {

  def testUrl(url: java.net.URL): Unit = {
    val result = try {
      val zip = new java.util.zip.ZipFile(new File(url.toURI))
      if (zip.getEntry("/xsbti/Logger.class") != null) true
      else false
    } catch {
      case e: java.io.IOException => false
    }
    if (result) println(url + " has xsbti.Logger class")
    else println(url + " - nada")
  }

  def printClassLoaders(cl: ClassLoader): Unit = cl match {
    case null => ()
    case url: java.net.URLClassLoader =>
      println("--== URLS ==--")
      url.getURLs foreach testUrl
      printClassLoaders(url.getParent)
    case cl =>
      println("--=== CL - " + cl.toString + " ===--")
      printClassLoaders(cl.getParent)
  }

  def run(configuration: xsbti.AppConfiguration) = {
    println("Starting dbuild...")
    val args = configuration.arguments
    //      println("Args (" + (configuration.arguments mkString ",") + ")")
    if (args.length != 1) {
      println("Usage: dbuild {build-file}")
      Exit(1)
    } else {
      try {
        val configFile = new File(args(0))
        if (!configFile.isFile())
          sys.error("Configuration file not found")
        println("Using configuration: " + configFile.getName)
        val (config, resolvers) =
          try {
            val properties = readProperties(configFile): Seq[String]
            val propConfigs = properties map { s =>
              println("Including properties file: " + s)
              val syntax = com.typesafe.config.ConfigSyntax.PROPERTIES
              val parseOptions = com.typesafe.config.ConfigParseOptions.defaults().setSyntax(syntax).setAllowMissing(false)
              val config = com.typesafe.config.ConfigFactory.parseURL(new java.net.URI(s).toURL, parseOptions)
              config.atKey("vars")
            }
            val initialConfig = com.typesafe.config.ConfigFactory.parseFile(configFile)
            val endConfig = propConfigs.foldLeft(initialConfig)(_.withFallback(_))
            //
            // deserialization will empty the Vars section. Before doing so, let's save beforehand the
            // (possible) list of resolvers defined in the configuration file, or in properties
            //
            val resolvedConfig = endConfig.resolve
            val explicitResolvers = SortedMap[String, (String, Option[String])]() ++ (if (resolvedConfig.hasPath("vars.dbuild.resolvers")) {
              import collection.JavaConverters._
              val map = resolvedConfig.getObject("vars.dbuild.resolvers").unwrapped().asScala
              map.map {
                case (k, v) => (k,
                  v match {
                    case s: String =>
                      s.split(":", 2) match {
                        case Array(x) => (x, None)
                        case Array(x, y) => (x, Some(y))
                        case z => sys.error("Internal error, unexpected split result: " + z)
                      }
                    case z => sys.error("Illegal resolver specification: must be a string, found :" + z)
                  })
              }
            } else Map.empty)
            //
            val conf = readValueT[DBuildConfiguration](endConfig)
            (conf, explicitResolvers.values)
          } catch {
            case e: Exception =>
              println("Error reading configuration file:")
              throw e
          }
        // Unique names?
        val allNames = config.build.projects map { _.name }
        val uniqueNames = allNames.distinct
        if (uniqueNames.size != allNames.size) {
          sys.error("Project names must be unique! Duplicates found: " + (allNames diff uniqueNames).mkString(","))
        }
        if (allNames.exists(_.size < 3)) {
          sys.error("Project names must be at least three characters long.")
        }
        //      println("Config: " + writeValue(config))
        //      println("Classloader:")
        //      printClassLoaders(getClass.getClassLoader)
        val repos = if (resolvers.isEmpty)
          configuration.provider.scalaProvider.launcher.ivyRepositories.toList
        else {
          val listMap = xsbt.boot.ListMap(resolvers.toSeq.reverse: _*)
          // getRepositories contains a ListMap.toList, where sbt's definition
          // of toList is "backing.reverse". So we have to reverse again.
          (new xsbt.boot.ConfigurationParser).getRepositories(listMap)
        }
        val main = new LocalBuildMain(repos)
        val (outcome, time) = try {
          timed { main.build(config, configFile.getName) }
        } finally main.dispose()
        println("Result: " + outcome.status)
        println("Build " + (if (outcome.isInstanceOf[BuildBad]) "failed" else "succeeded") + " after: " + time)
        println("All done.")
        if (outcome.isInstanceOf[BuildBad]) Exit(1) else Exit(0)
      } catch {
        case e: Exception =>
          println("An exception occurred.")
          e.printStackTrace
          Exit(1)
      }
    }
  }
  case class Exit(val code: Int) extends xsbti.Exit
} 