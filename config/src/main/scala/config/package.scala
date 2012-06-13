
package object config {
  import com.typesafe.{ config => j }
  import config._
  
  type ConfigOrigin = j.ConfigOrigin
  type ConfigRenderOptions = j.ConfigRenderOptions
  type ConfigParseOptions = j.ConfigParseOptions
  type ConfigResolveOptions = j.ConfigResolveOptions
  type ConfigException = j.ConfigException
  type ConfigObject = j.ConfigObject
  type ConfigValue = j.ConfigValue
  type ConfigList = j.ConfigList
  type ConfigValueType = j.ConfigValueType
  type Config = j.Config
  
  
  def parseFile(f: java.io.File, options: ConfigParseOptions = j.ConfigParseOptions.defaults) = j.ConfigFactory.parseFile(f)
  def parse(r: java.io.Reader, options: ConfigParseOptions = j.ConfigParseOptions.defaults) = j.ConfigFactory.parseReader(r, options)
  def parseString(in: String, options: ConfigParseOptions = j.ConfigParseOptions.defaults) = j.ConfigFactory.parseString(in, options)
  def load(cl: ClassLoader = Thread.currentThread.getContextClassLoader) = j.ConfigFactory.load(cl)
  def loadWithFallback(
      fallback: Config, 
      cl: ClassLoader = Thread.currentThread.getContextClassLoader, 
      options: ConfigResolveOptions = j.ConfigResolveOptions.defaults) =
    j.ConfigFactory.load(cl, fallback, options)
    
  // TODO - parseMap
}