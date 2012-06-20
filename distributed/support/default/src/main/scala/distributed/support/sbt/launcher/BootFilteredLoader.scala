package distributed.support.sbt.launcher


import xsbt.boot.Loaders

final class BootFilteredLoader(parent: ClassLoader) extends ClassLoader(parent)
{
  @throws(classOf[ClassNotFoundException])
  override final def loadClass(className: String, resolve: Boolean): Class[_] =
    // note that we allow xsbti.* and jline.*
    if(className.startsWith("xsbti.") || 
       className.startsWith("jline.") ||
       className.startsWith("org.xml.") ||
       className.startsWith("java.") ||
       className.startsWith("sun.") ||
       className.startsWith("javax.")) {
      super.loadClass(className, resolve)
    } else throw new ClassNotFoundException(className)
  override def getResources(name: String) = if(includeResource(name)) super.getResources(name) else excludedLoader.getResources(name)
  override def getResource(name: String) = if(includeResource(name)) super.getResource(name) else excludedLoader.getResource(name)
  def includeResource(name: String) = name.startsWith("jline/")
  // the loader to use when a resource is excluded.  This needs to be at least parent.getParent so that it skips parent.  parent contains
  // resources included in the launcher, which need to be ignored.  Now that launcher can be unrooted (not the application entry point),
  // this needs to be the Java extension loader (the loader with getParent == null)
  private val excludedLoader = {
    def getLoader(cl: ClassLoader): ClassLoader =
      if(cl.getParent == null) cl
      else getLoader(cl.getParent)
    getLoader(parent)
  }
}