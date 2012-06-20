package distributed
package support
package sbt
package launcher

import xsbt.boot.{
  Application,
  AppID,
  Classifiers,
  Launcher, 
  IvyOptions, 
  Cache, 
  Locks, 
  ModuleDefinition, 
  UpdateConfiguration, 
  UpdateScala, 
  Value, 
  RetrievedModule, 
  BootConfiguration, 
  UpdateApp, 
  UpdateTarget, 
  Update,
  Loaders,
  ComponentProvider,
  RunConfiguration,
  AppConfiguration,
  Repository
}
import xsbt.boot.Pre._
import xsbti.{GlobalLock}
import _root_.java.util.concurrent.Callable
import _root_.java.io.File


class MySbtRunner(val bootDirectory: File) {
  protected lazy val launcher = new MyLauncher(
      bootDirectory = bootDirectory,
      lockBoot = true,
      ivyOptions = new IvyOptions(
        ivyHome = None,
        classifiers = Classifiers(List.empty, List.empty),
        repositories = List(
            Repository.Predefined("local"),
            Repository.Predefined("maven-central")),
        checksums = List("sha1","md5"),
        isOverrideRepositories = false  // TODO - make this true!!!
      ))
  
  
  def runCommand(workingDirectory: File, cmd: String) =
    MyLauncher.runSbtCommand(cmd, workingDirectory, launcher)
}

object MyLauncher {
  /**
   *   org: ${sbt.organization-${{org}}}
  name: sbt
  version: ${sbt.version-read(sbt.version)[${{sbt.version}}]}
  class: ${sbt.main.class-sbt.xMain}
  components: xsbti,extra
  cross-versioned: ${sbt.cross.versioned-false}

   */
  def runSbtCommand(cmd: String, workingDir: File, launcher: xsbti.Launcher): Unit = {
    System.setProperty("sbt.log.format", "false")
    val config = new RunConfiguration(
        scalaVersion = None,
        app = new AppID(
          groupID = "org.scala-sbt",
          name = "sbt",
          version = "0.12.0-RC2",
          mainClass = "sbt.xMain",
          mainComponents = Array("xsbti", "extra"),
          crossVersioned = false,
          classpathExtra = Array.empty
        ),
        workingDirectory = workingDir,
        arguments = List(cmd)
    )
    run(launcher)(config)
  }
  
  // Runs an application and returns the result or None if an exit exception was thrown.
  def run(launcher: xsbti.Launcher)(config: RunConfiguration): Option[xsbti.MainResult] = ExitSecurity.withNoExits {
    import config._
    val appProvider: xsbti.AppProvider = launcher.app(app, orNull(scalaVersion)) // takes ~40 ms when no update is required
    val appConfig: xsbti.AppConfiguration = new AppConfiguration(toArray(arguments), workingDirectory, appProvider)
    val main = appProvider.newMain()
    try { main.run(appConfig) }
    catch { case e: xsbti.FullReload => if(e.clean) _root_.sbt.IO.delete(launcher.bootDirectory); throw e }
  }
}

class MyLauncher(val bootDirectory: File, val lockBoot: Boolean, val ivyOptions: IvyOptions) extends {
  override val checksums: Array[String] = ivyOptions.checksums.toArray
  override val ivyHome: File = ivyOptions.ivyHome.getOrElse(new File(System.getProperty("user.home") + "/.ivy2"))
  override val isOverrideRepositories = ivyOptions.isOverrideRepositories
  override val ivyRepositories = ivyOptions.repositories.toArray
  override val globalLock = Locks
} with xsbti.Launcher with Locking with Modules with ScalaProviders with Loaders  {
  // Ensure we have a boot directory.
  bootDirectory.mkdirs
}


/** Locking behavior for our launcher. */
trait Locking { self: MyLauncher =>
  protected def locked[T](c: Callable[T]): T = Locks(orNull(updateLockFile), c)
  val updateLockFile = if(lockBoot) Some(new File(bootDirectory, "sbt.boot.lock")) else None
}

trait Modules { self: MyLauncher => 
  def checkLoader[T](loader: ClassLoader, module: ModuleDefinition, testClasses: Seq[String], ifValid: T): T = {
    val missing = getMissing(loader, testClasses)
    if(missing.isEmpty)
      ifValid
    else
      module.retrieveCorrupt(missing)
  }
  def existing(module: ModuleDefinition, scalaOrg: String, explicitScalaVersion: Option[String], baseDirs: File => List[File]): Option[RetrievedModule] = {
    val filter = new _root_.java.io.FileFilter {
      val explicitName = explicitScalaVersion.map(sv => baseDirectoryName(scalaOrg, Some(sv)))
      def accept(file: File) = file.isDirectory && explicitName.forall(_ == file.getName)
    }
    val retrieved = wrapNull(bootDirectory.listFiles(filter)) flatMap { scalaDir =>
      val appDir = directory(scalaDir, module.target)
      if(appDir.exists)
        new RetrievedModule(false, module, extractScalaVersion(scalaDir), baseDirs(scalaDir)) :: Nil
      else
        Nil
    }
    retrieved.headOption
  }
  def directory(scalaDir: File, target: UpdateTarget): File = 
    target match {
      case _: UpdateScala => scalaDir
      case ua: UpdateApp => appDirectory(scalaDir, ua.id.toID)
    }
  
  def extractScalaVersion(dir: File): Option[String] = {
    val name = dir.getName
    if(name.contains("scala-"))
      Some(name.substring(name.lastIndexOf("scala-") + "scala-".length))
    else
      None
  }
  def baseDirectoryName(scalaOrg: String, scalaVersion: Option[String]) = 
    scalaVersion match {
      case None => "other"
      case Some(sv) => (if (scalaOrg == ScalaOrg) "" else scalaOrg + ".") + "scala-" + sv
    }
  def appDirectory(base: File, id: xsbti.ApplicationID): File =
    new File(base, appDirectoryName(id, File.separator))
  
  def appDirectoryName(appID: xsbti.ApplicationID, sep: String) = appID.groupID + sep + appID.name + sep + appID.version
  
  def update(mm: ModuleDefinition, reason: String): Option[String] = {
    val result = ( new Update(mm.configuration) )(mm.target, reason)
    if(result.success) result.scalaVersion else mm.retrieveFailed
  }
  def appModule(id: xsbti.ApplicationID, scalaVersion: Option[String], getClassifiers: Boolean, tpe: String): ModuleDefinition = new ModuleDefinition(
    configuration = makeConfiguration(ScalaOrg, scalaVersion),
    target = new UpdateApp(Application(id), if(getClassifiers) Value.get(ivyOptions.classifiers.app) else Nil, tpe),
    failLabel = id.name + " " + id.version,
    extraClasspath = id.classpathExtra
  )
  
  // TODO - App stuff belong somewhere else to unclutter?
  def app(id: xsbti.ApplicationID, version: String): xsbti.AppProvider = app(id, Option(version))
  
  def app(id: xsbti.ApplicationID, scalaVersion: Option[String]): xsbti.AppProvider =
      getAppProvider(id, scalaVersion, false)
  final def getAppProvider(id: xsbti.ApplicationID, explicitScalaVersion: Option[String], forceAppUpdate: Boolean): xsbti.AppProvider =
    locked(new Callable[xsbti.AppProvider] { def call = getAppProvider0(id, explicitScalaVersion, forceAppUpdate) })

  @annotation.tailrec private final def getAppProvider0(id: xsbti.ApplicationID, explicitScalaVersion: Option[String], forceAppUpdate: Boolean): xsbti.AppProvider = {
    val app = appModule(id, explicitScalaVersion, true, "app")
    val baseDirs = (base: File) => appBaseDirs(base, id)
    def retrieve() = {
      val sv = update(app, "")
      val scalaVersion = strictOr(explicitScalaVersion, sv)
      new RetrievedModule(true, app, sv, baseDirs(scalaHome(ScalaOrg, scalaVersion)))
    }
    val retrievedApp =
      if(forceAppUpdate)
        retrieve()
      else
        existing(app, ScalaOrg, explicitScalaVersion, baseDirs) getOrElse retrieve()

    val scalaVersion = getOrError(strictOr(explicitScalaVersion, retrievedApp.detectedScalaVersion), "No Scala version specified or detected")
    val scalaProvider = getScala(scalaVersion, "(for " + id.name + ")")

    val (missing, appProvider) = checkedAppProvider(id, retrievedApp, scalaProvider)
    if(missing.isEmpty) appProvider
    else if(retrievedApp.fresh) app.retrieveCorrupt(missing)
    else getAppProvider0(id, explicitScalaVersion, true)
  }
  def checkedAppProvider(id: xsbti.ApplicationID, module: RetrievedModule, scalaProvider: xsbti.ScalaProvider): (Iterable[String], xsbti.AppProvider) = {
    val p = appProvider(id, module, scalaProvider, appHome(id, Some(scalaProvider.version)))
    val missing = getMissing(p.loader, id.mainClass :: Nil)
    (missing, p)        
  }
  def appBaseDirs(scalaHome: File, id: xsbti.ApplicationID): List[File] = {
    val appHome = appDirectory(scalaHome, id)
    val components = componentProvider(appHome)
    appHome :: id.mainComponents.map(components.componentLocation).toList
  }
  def componentProvider(appHome: File) = new ComponentProvider(appHome, lockBoot)
  def appProvider(appID: xsbti.ApplicationID, app: RetrievedModule, scalaProvider0: xsbti.ScalaProvider, appHome: File): xsbti.AppProvider = 
    new xsbti.AppProvider {
      val scalaProvider = scalaProvider0
      val id = appID
      def mainClasspath = app.fullClasspath
      lazy val loader = app.createLoader(scalaProvider.loader)
      lazy val mainClass: Class[T] forSome { type T <: xsbti.AppMain } = {
        val c = Class.forName(id.mainClass, true, loader)
        c.asSubclass(classOf[xsbti.AppMain])
      }
      def newMain(): xsbti.AppMain = mainClass.newInstance
      lazy val components = componentProvider(appHome)
    }
  def scalaHome(scalaOrg: String, scalaVersion: Option[String]): File = new File(bootDirectory, baseDirectoryName(scalaOrg, scalaVersion))
  def appHome(id: xsbti.ApplicationID, scalaVersion: Option[String]): File = appDirectory(scalaHome(ScalaOrg, scalaVersion), id)
}

/** Scala Provider behavior for our launcher. */
trait ScalaProviders { self: MyLauncher =>
  val ScalaOrg = "org.scala-lang"
  // Scala Providers
  private val scalaProviders = new Cache[(String, String), String, xsbti.ScalaProvider]((x, y) => getScalaProvider(x._1, x._2, y))
  override def getScala(version: String): xsbti.ScalaProvider = getScala(version, "")
  override def getScala(version: String, reason: String): xsbti.ScalaProvider = getScala(version, reason, ScalaOrg)
  override def getScala(version: String, reason: String, scalaOrg: String): xsbti.ScalaProvider = scalaProviders((scalaOrg, version), reason)
  
  def getScalaProvider(scalaOrg: String, scalaVersion: String, reason: String): xsbti.ScalaProvider =
    locked(new Callable[xsbti.ScalaProvider] { 
      def call = getScalaProvider0(ScalaOrg, scalaVersion, reason) 
    })
    
  final def getScalaProvider0(scalaOrg: String, scalaVersion: String, reason: String) = {
    val scalaM = scalaModule(scalaOrg, scalaVersion)
    val (scalaHome, lib) = scalaDirs(scalaM, scalaOrg, scalaVersion)
    val baseDirs = lib :: Nil
    def provider(retrieved: RetrievedModule): xsbti.ScalaProvider = {
      val p = scalaProvider(scalaVersion, retrieved, topLoader, lib)
      checkLoader(p.loader, retrieved.definition, TestLoadScalaClasses, p)
    }
    existing(scalaM, scalaOrg, Some(scalaVersion), _ => baseDirs) flatMap { mod =>
      try Some(provider(mod))
      catch { case e: Exception => None }
    } getOrElse {
      val scalaVersion = update(scalaM, reason)
      provider( new RetrievedModule(true, scalaM, scalaVersion, baseDirs) )
    }
  }
  def scalaModule(org: String, version: String): ModuleDefinition = 
    new ModuleDefinition(
      configuration = makeConfiguration(org, Some(version)),
      target = new UpdateScala(Value.get(ivyOptions.classifiers.forScala)),
      failLabel = "Scala " + version,
      extraClasspath = Array.empty
    )
  def scalaDirs(module: ModuleDefinition, scalaOrg: String, scalaVersion: String): (File, File) = { 
    val scalaHome = new File(bootDirectory, baseDirectoryName(scalaOrg, Some(scalaVersion)))
    val libDirectory = new File(scalaHome, "lib")
    (scalaHome, libDirectory)
  }
  protected final def makeConfiguration(scalaOrg: String, version: Option[String]): UpdateConfiguration =
    new UpdateConfiguration(bootDirectory, ivyOptions.ivyHome, scalaOrg, version, ivyOptions.repositories, ivyOptions.checksums)
  
  def scalaProvider(scalaVersion: String, module: RetrievedModule, parentLoader: ClassLoader, scalaLibDir: File): xsbti.ScalaProvider =
    new xsbti.ScalaProvider{
      def launcher = self
      def version = scalaVersion
      lazy val loader = module.createLoader(parentLoader)
      def compilerJar = new File(scalaLibDir, "scala-compiler.jar")
      def libraryJar = new File(scalaLibDir, "scala-library.jar")
      def jars = module.fullClasspath
      def app(id: xsbti.ApplicationID) = self.app(id, scalaVersion)
    }
  
  val TestLoadScalaClasses = "scala.Option" :: "scala.tools.nsc.Global" :: Nil
}


trait Loaders { self: MyLauncher =>
  val bootLoader = new BootFilteredLoader(classOf[xsbti.Launcher].getClassLoader)
  val topLoader = jnaLoader(bootLoader)  
  def jnaLoader(parent: ClassLoader): ClassLoader = {
    val id = AppID("net.java.dev.jna", "jna", "3.2.3", "", toArray(Nil), false, array())
    val configuration = makeConfiguration(ScalaOrg, None)
    val jnaHome = appDirectory(new File(bootDirectory, baseDirectoryName(ScalaOrg, None)), id)
    val module = appModule(id, None, false, "jna")
    def makeLoader(): ClassLoader = {
      val urls = toURLs(wrapNull(jnaHome.listFiles(JarFilter)))
      val loader = new _root_.java.net.URLClassLoader(urls, bootLoader)
      checkLoader(loader, module, "com.sun.jna.Function" :: Nil, loader)
    }
    val existingLoader =
      if(jnaHome.exists)
        try Some(makeLoader()) catch { case e: Exception => None }
      else
        None
    existingLoader getOrElse {
      update(module, "")
      makeLoader()
    }
  }
}