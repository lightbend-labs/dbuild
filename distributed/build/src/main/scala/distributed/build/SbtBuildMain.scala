package distributed
package build

import java.io.File
import distributed.project.model.{BuildArtifacts,DistributedBuildConfig}
import config.{parseFileInto, makeConfigString}

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
      println("Args (" + (configuration.arguments mkString ",") + ")")
      val config = 
        if(args.length == 1) 
          parseFileInto[DistributedBuildConfig](new File(args(0))) match{
            case Some(b) => b
            case _ => sys.error("Failure reading build file: " + args(0))
          }
        else sys.error("Usage: dbuild {build-file}")
      println("Config: " + makeConfigString(config))
      println("Classloader:")
      printClassLoaders(getClass.getClassLoader)
      val main = new LocalBuildMain(configuration.baseDirectory)
      try main build config
      finally main.dispose()
      Exit(0)
    } catch {
      case e: Exception =>
        e.printStackTrace
        Exit(1)
    }
  case class Exit(val code: Int) extends xsbti.Exit
} 