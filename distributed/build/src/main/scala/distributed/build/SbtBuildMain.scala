package distributed
package build

import java.io.File
import distributed.project.model.DBuildConfiguration
import distributed.project.model.Utils.{ writeValue, readValue, readProperties }
import distributed.project.model.ClassLoaderMadness
import distributed.project.model.{ BuildOutcome, BuildBad }
import distributed.project.model.TemplateFormatter
import distributed.project.model.CleanupOptions
import java.util.Properties
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import distributed.project.model.Utils.readValueT
import distributed.utils.Time.timed
import collection.immutable.SortedMap
import distributed.repo.core.Defaults
import com.typesafe.config.{ ConfigSyntax, ConfigFactory, ConfigParseOptions }
import org.rogach.scallop._
import org.rogach.scallop.exceptions.ScallopException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import collection.JavaConverters._

/** These options are created by SbtBuildMain, and are propagated to most stages of building, as
 *  they alter secondary details of the build process in various ways (for example, the logging
 *  level, or how frequently old data is deleted).
 */
case class BuildRunOptions(cleanup: CleanupOptions, debug: Boolean, defaultNotifications: Boolean)

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
    //    
    // We use an option parsing library, therefore we would be able
    // to parse and process our properties and options quite easily.
    // However, the sbt launcher performs some preprocessing,
    // preventing us from getting to all of the options. In particular:
    //
    // 1) --version is captured by the sbt launcher. In order to get the
    //    dbuild version, one needs to use "-- --version"
    // 2) The -D processing is performed by the sbt launcher. Although
    //    we do list -D among the options, when we get to this main method
    //    the properties have already been inserted into the environment.
    //    If we use "-- -Dxxx=value", however, we end up with stuff inside
    //    conf.properties, below (which we normally ignore instead)
    //
    // All this is rather suboptimal, but there is no way to prevent the
    // sbt launcher from stealing part of the command line options (it does it
    // first thing upon start, in sbt/launch/src/main/scala/xsbt/boot/Boot.scala)
    //
    val args = configuration.arguments
    object conf extends ScallopConf(args.toList) {
      printedName = "dbuild"
      version("Typesafe dbuild "+ Defaults.version)
      banner("""Usage: dbuild [OPTIONS] config-file [target]
               |dbuild is a multi-project build tool that can verify the compatibility
               |of multiple related projects, by building each one on top of the others.
               |Options:
               |""".stripMargin)
      footer("\nFor more information: http://typesafehub.github.io/distributed-build")
      val properties = props[String](descr = "One or more Java-style properties")
      val configFile = trailArg[String](required = true, descr = "The name of the dbuild configuration file")
      val target = trailArg[String](required = false, descr = "If a target project name is specified, dbuild will build only that project and its dependencies")
      val debug = opt[Boolean](descr = "Print more debugging information")
      val noResolvers = opt[Boolean](short = 'r', descr = "Disable the parsing of the \"options.resolvers\" section from the dbuild configuration file: only use the resolvers defined in dbuild.properties")
      val noNotify = opt[Boolean](short = 'n', descr = "Disable the notifications defined in the configuration file, and only print a report on the console")
      val local = opt[Boolean](short = 'l', descr = "Equivalent to: --no-resolvers --no-notify")
    }
    try {
      val configFile = new File(conf.configFile())
      if (!configFile.isFile())
        sys.error("Configuration file \"" + conf.configFile() + "\" not found")
      val debug = conf.debug()
      val useLocalResolvers = conf.noResolvers() || conf.local()
      val defaultNotifications = conf.noNotify() || conf.local()
      val buildTarget = conf.target.get
      if (debug) {
        println("Using configuration: " + configFile.getName)
        buildTarget foreach { t => println("Build target: " + t) }
      }
      val now = new java.util.Date()
      val dateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'UTC'")
      dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
      val autoTimestamp = dateFormat.format(now)
      if (debug) println("The timestamp is: " + autoTimestamp)
      val (config, resolvers) =
        try {
          val properties = readProperties(configFile): Seq[String]
          val propConfigs = properties.reverse map { s =>
            if (debug) println("Including properties file: " + s)
            val syntax = ConfigSyntax.PROPERTIES
            val parseOptions = ConfigParseOptions.defaults().setSyntax(syntax).setAllowMissing(false)
            val url = try {
              new java.net.URI(s).toURL
            } catch {
              case e: IllegalArgumentException =>
                println("Malformed reference to external properties (did you forget \"file:\"?): " + s)
                throw e
            }
            val config = ConfigFactory.parseURL(url, parseOptions)
            config.atKey("vars")
          }
          val initialConfig = ConfigFactory.parseFile(configFile)
          val foldConfig = propConfigs.foldLeft(initialConfig)(_.withFallback(_))
          val systemVars = ConfigFactory.systemProperties().atPath("vars.sys")
          val autoConfig = ConfigValueFactory.fromMap(Map("timestamp" -> autoTimestamp).asJava).atPath("vars.auto")
          // let system properties take precedence over values in the config file
          // which should happen to be in the same vars.sys space
          val endConfig = systemVars.withFallback(autoConfig).withFallback(foldConfig)
          val resolvedConfig = endConfig.resolve
          // Let's extract the (possible) list of resolvers defined in the configuration file.
          // Parse them even if useLocalResolvers==true, in order to catch definition errors
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
      if (debug) {
        println("Config: " + writeValue(config))
        println("Classloader:")
        printClassLoaders(getClass.getClassLoader)
      }
      val repos = if (useLocalResolvers || resolvers.isEmpty)
        configuration.provider.scalaProvider.launcher.ivyRepositories.toList
      else {
        val listMap = xsbt.boot.ListMap(resolvers.toSeq.reverse: _*)
        // getRepositories contains a ListMap.toList, where sbt's definition
        // of toList is "backing.reverse". So we have to reverse again.
        (new xsbt.boot.ConfigurationParser).getRepositories(listMap)
      }
      if (debug) {
        println("Resolvers:")
        repos foreach println
      }
      //
      // create a replacement "option.resolvers" section, that will be included
      // in the repeatable configuration we will save after extraction
      //
      val repoStrings: List[String] = repos map {
        case m: xsbti.MavenRepository => m.id + ": " + m.url
        case i: xsbti.IvyRepository => i.id + ": " + i.url + ", " + i.ivyPattern
        case p: xsbti.PredefinedRepository => p.id.toString
      }
      val repoMap = (repoStrings.zipWithIndex map {
        case (str, index) =>
          val label = "R" + (("0000" + index.toString).reverse.take(4).reverse)
          label -> str
      }).toMap
      val finalConfig = config.copy(options = config.options.copy(resolvers = repoMap))

      val main = new LocalBuildMain(repos, BuildRunOptions(finalConfig.options.cleanup, debug, defaultNotifications))
      val (outcome, time) = try {
        timed { main.build(finalConfig, configFile.getName, buildTarget) }
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
  case class Exit(val code: Int) extends xsbti.Exit
} 
