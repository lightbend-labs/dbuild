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
import com.typesafe.config.{ ConfigSyntax, ConfigFactory, ConfigParseOptions }

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
    if (args.length != 1 && args.length != 2) {
      println("Usage: dbuild {build-file} [{build-target}]")
      Exit(1)
    } else {
      try {
        val configFile = new File(args(0))
        if (!configFile.isFile())
          sys.error("Configuration file not found")
        println("Using configuration: " + configFile.getName)
        val buildTarget = if (args.length == 2) Some(args(1)) else None
        buildTarget foreach { t => println("Build target: " + t) }
        val (config, resolvers) =
          try {
            val properties = readProperties(configFile): Seq[String]
            val propConfigs = properties map { s =>
              println("Including properties file: " + s)
              val syntax = ConfigSyntax.PROPERTIES
              val parseOptions = ConfigParseOptions.defaults().setSyntax(syntax).setAllowMissing(false)
              val config = ConfigFactory.parseURL(new java.net.URI(s).toURL, parseOptions)
              config.atKey("vars")
            }
            val initialConfig = ConfigFactory.parseFile(configFile)
            val foldConfig = propConfigs.foldLeft(initialConfig)(_.withFallback(_))
            val systemVars = ConfigFactory.systemProperties().atPath("vars.sys")
            // let system properties take precedence over values in the config file
            // which should happen to be in the same vars.sys space
            val endConfig = systemVars.withFallback(foldConfig)
            val resolvedConfig = endConfig.resolve
            // Let's extract the (possible) list of resolvers defined in the configuration file
            val explicitResolvers = SortedMap[String, (String, Option[String])]() ++
              (if (resolvedConfig.hasPath("options.resolvers")) {
                import collection.JavaConverters._
                val map = resolvedConfig.getObject("options.resolvers").unwrapped().asScala
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
            // After deserialization, Vars is empty (see VarDeserializer)
            // Let's also empty the list of property files, which is now no longer needed
            val conf = readValueT[DBuildConfiguration](endConfig).copy(properties = Seq.empty)
            (conf, explicitResolvers.values)
          } catch {
            case e: Exception =>
              println("Error reading configuration file:")
              throw e
          }
        // Unique names?
        val allNames = config.build.flatMap { _.projects map { _.name } }
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
        val main = new LocalBuildMain(repos, config.options.cleanup)
        val (outcome, time) = try {
          timed { main.build(config, configFile.getName, buildTarget) }
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