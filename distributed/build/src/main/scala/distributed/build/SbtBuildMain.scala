package distributed
package build

import java.io.File
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import distributed.project.model.{BuildArtifactsOut,DBuildConfiguration}
import distributed.project.model.Utils.{writeValue,readValue}
import distributed.project.model.ClassLoaderMadness

/** An Sbt buiild runner. */
class SbtBuildMain extends xsbti.AppMain {
  
  def testUrl(url: java.net.URL): Unit = {
    val result = try {
      val zip = new java.util.zip.ZipFile(new File(url.toURI))
      if(zip.getEntry("/xsbti/Logger.class") != null) true
      else false
    } catch {
      case e: java.io.IOException => false
    }
    if(result)  println(url + " has xsbti.Logger class")
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
  
  def run(configuration: xsbti.AppConfiguration) =
    try {
    println("Starting dbuild...")
      val args = configuration.arguments
//      println("Args (" + (configuration.arguments mkString ",") + ")")
      val config = 
        if(args.length == 1)
          readValue[DBuildConfiguration](new File(args(0)))
        else sys.error("Usage: dbuild {build-file}")
      // Unique names?
      val allNames=config.build.projects map {_.name}
      val uniqueNames=allNames.distinct
      if (uniqueNames.size != allNames.size) {
        sys.error("Project names must be unique! Duplicates found: "+(allNames diff uniqueNames).mkString(","))
      }
      if (allNames.exists(_.size <3)) {
        sys.error("Project names must be at least three characters long.")
      }
//      println("Config: " + writeValue(config))
//      println("Classloader:")
//      printClassLoaders(getClass.getClassLoader)
      val main = new LocalBuildMain(configuration)
      try {
        def time[A](f: => A) = {
          val s = System.nanoTime
          val ret = f
          val t = System.nanoTime - s
          // Braindead SimpleDateFormat messes up 'S' format
          val time = new Date(t / 1000000L)
          val tenths = (t / 100000000L) % 10L
          val sdf = new SimpleDateFormat("HH'h' mm'm' ss'.'")
          sdf.setTimeZone(TimeZone.getTimeZone("GMT"))
          println("Build took: " + sdf.format(time) + tenths + "s")
          ret
        }
        time { main build config }
        println("All done.")
      }
      finally main.dispose()
      Exit(0)
    } catch {
      case e: Exception =>
        e.printStackTrace
        Exit(1)
    }
  case class Exit(val code: Int) extends xsbti.Exit
} 