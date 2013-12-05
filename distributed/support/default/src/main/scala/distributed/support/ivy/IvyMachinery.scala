package distributed.support.ivy

import distributed.project.BuildSystem
import distributed.project.model._
import java.io.File
import sbt.Path._
import sbt.IO.relativize
import distributed.logging.Logger
import sys.process._
import distributed.repo.core.LocalRepoHelper
import distributed.project.model.Utils.readValue
import xsbti.Predefined._
import org.apache.ivy
import ivy.Ivy
import ivy.plugins.resolver.{ BasicResolver, ChainResolver, FileSystemResolver, IBiblioResolver, URLResolver }
import ivy.core.settings.IvySettings
import ivy.core.module.descriptor.{ DefaultModuleDescriptor, DefaultDependencyDescriptor, Artifact }
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorWriter
import ivy.core.module.id.{ ModuleId, ModuleRevisionId }
import ivy.core.resolve.{ ResolveEngine, ResolveOptions }
import ivy.core.report.ResolveReport
import sbt.FileRepository
import org.apache.ivy.core.retrieve.RetrieveOptions
import org.apache.ivy.core.deliver.DeliverOptions
import distributed.support.sbt.Repositories.ivyPattern
import org.apache.ivy.core.resolve.IvyNode
import org.apache.ivy.core.module.descriptor.Configuration
import org.apache.ivy.core.module.descriptor.DefaultDependencyArtifactDescriptor

object IvyMachinery {
  // there are two stages to the madness below. The first: we create a dummy caller, and add the module we need as a dependency.
  // That is used for extraction, and also later during build to re-download the artifacts we need.
  // The second (optional) stage: we retrieve those artifacts (storing them in a given directory), and then we create a new
  // descriptor that will become the ivy.xml of the reconstructed module, to which we add the dependencies of our module.

  case class ResolveResponse(theIvy: Ivy, report: ResolveReport, resolveOptions: ResolveOptions,
    modRevId: ModuleRevisionId, allConfigs: Array[String])

  def resolveIvy(config: ProjectBuildConfig, baseDir: File, repos: List[xsbti.Repository], log: Logger,
    transitive: Boolean = true, useOptional: Boolean = true): ResolveResponse = {
    log.info("Running Ivy to extract project info: " + config.name)
    val extra = config.getExtra[IvyExtraConfig]
    import extra._
    // this is the one local to the project (extraction or build)
    val ivyHome = (baseDir / ".ivy2")
    if (!config.uri.startsWith("ivy:"))
      sys.error("Fatal: the uri in Ivy project " + config.name + " does not start with \"ivy:\"")
    val module = config.uri.substring(4)
    log.debug("requested module is: " + module)
    val settings = new IvySettings()
    settings.setDefaultIvyUserDir(ivyHome)
    val dbuildRepoDir = baseDir / ".dbuild" / "local-repo"
    addResolvers(settings, ivyHome, repos, dbuildRepoDir)
    settings.setDefaultCacheArtifactPattern(ivyPattern)
    val cache = settings.getDefaultCache()
    val theIvy = Ivy.newInstance(settings)
    theIvy.getLoggerEngine.pushLogger(new IvyLoggerInterface(log))
    sbt.IO.withTemporaryFile("ivy", ".xml") { ivyFile =>
      val outer = ModuleRevisionId.newInstance("dbuild-ivy", "dbuild-ivy", "working")
      val md = new DefaultModuleDescriptor(outer, "integration", new java.util.Date())
      md.addExtraAttributeNamespace("m", "http://ant.apache.org/ivy/maven")
      val modRevId = ModuleRevisionId.parse(module)
      val dd = new DefaultDependencyDescriptor(md,
        modRevId, /*force*/ true, /*changing*/ modRevId.getRevision.endsWith("-SNAPSHOT"), /*transitive*/ transitive && mainJar)
      // if !mainJar and no other source/javadoc/classifier, will pick default artifact (usually the jar)

      def addArtifact(classifier: String, typ: String = "jar", ext: String = "jar", configs: Seq[String]) = {
        val classif = new java.util.HashMap[String, String]()
        if (classifier != "") classif.put("m:classifier", classifier)
        val art = new DefaultDependencyArtifactDescriptor(
          dd,
          modRevId.getName,
          typ, ext, null, classif)
        configs foreach { c =>
          art.addConfiguration(c)
          dd.addDependencyArtifact(c, art)
        }
      }

      if (sources) addArtifact("sources", "src", configs = Seq("sources"))
      if (javadoc) addArtifact("javadoc", "doc", configs = Seq("javadoc"))
      if (mainJar) addArtifact("", "jar", configs = Seq("compile"))
      artifacts foreach { a => addArtifact(a.classifier, a.typ, a.ext, configs = a.configs) }
      val configs = dd.getAllDependencyArtifacts().flatMap(arts => arts.getConfigurations())
      // In order to support optional dependencies, I need to resolve using also
      // the "optional" configuration; the returned artifacts (if any) need to be added
      // below, during publishing, to the "optional" Ivy configuration.
      val allConfigs = if (useOptional) configs :+ "optional" else configs
      // do /not/ add "default" by default, otherwise the default mapping *->* will drag in also optional libraries,
      // which we do not want (unless explicitly requested)
      allConfigs foreach {c=>md.addConfiguration(new Configuration(c))}
      if (allConfigs contains "compile") {
        dd.addDependencyConfiguration("compile", "default(compile)")
      }
      allConfigs.diff(Seq("compile")) foreach { c =>
        dd.addDependencyConfiguration(c, c)
      }
      md.addDependency(dd)

      if (dd.getAllDependencyArtifacts.isEmpty)
        sys.error("The list of artifacts is empty: please enable at least one artifact.")

      //creates an ivy configuration file
      XmlModuleDescriptorWriter.write(md, ivyFile)
      scala.io.Source.fromFile(ivyFile).getLines foreach { s => log.debug(s) }
      log.debug("These configs will be used for resolution: " + allConfigs.mkString(","))

      val resolveOptions = new ResolveOptions().setConfs(allConfigs)
      //val resolveOptions = new ResolveOptions().setConfs(Seq[String]("optional").toArray)
      resolveOptions.setLog(org.apache.ivy.core.LogOptions.LOG_DOWNLOAD_ONLY)

      val report: ResolveReport = theIvy.resolve(ivyFile.toURL(), resolveOptions)
      if (report.hasError) sys.error("Ivy resolution failure")
      import scala.collection.JavaConversions._
      val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]]
      if (nodes.isEmpty) {
        sys.error("Ivy error: no nodes after resolve. Please report.")
      }
      val firstNode = nodes.get(0)
      if (!firstNode.isLoaded) {
        sys.error("Unexpected Ivy error: node not loaded (" + firstNode.getModuleId + ")")
      }

      // diagnostic
      log.debug("Report:")
      nodes foreach { n =>
        log.debug("Node: " + n + (if (n.isLoaded) " (loaded)" else " (not loaded)"))
        log.debug("is optional: " + n.getConfigurations("optional").nonEmpty)
        if (n.isLoaded) {
          log.debug("Artifacts:")
          n.getAllArtifacts() foreach { a => log.debug("  " + a+" configs:"+a.getConfigurations.mkString(",")) }
        }
      }

      ResolveResponse(theIvy, report, resolveOptions, modRevId, allConfigs)
    }
  }

  case class PublishIvyInfo(art: Artifact, rewritten: Boolean, optional: Boolean)
  
  // publishIvy works by first retrieving the resolved artifacts (only the artifacts we need to republish, hence transitive is false
  // in the resolve whose report we use here). Then, we generate the ixy.xml.
  def publishIvy(response: ResolveResponse, ivyxmlDir: File, deps: Seq[PublishIvyInfo], publishVersion: String, log: Logger) = {
    val ResolveResponse(theIvy: Ivy, report, resolveOptions, modRevId, allConfigs) = response
    val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]]
    val firstNode = nodes.get(0)

    //
    // retrieval of artifacts (aka: we take the resolved artifacts and we store them into the dest repo,
    // using a custom pattern)
    //
    val caller = report.getModuleDescriptor.getModuleRevisionId
    val ro = new org.apache.ivy.core.retrieve.RetrieveOptions
    ro.setLog(org.apache.ivy.core.LogOptions.LOG_DOWNLOAD_ONLY)
    ro.setConfs(allConfigs)
    // we use a modified retrieve pattern, in order to store artifacts in the proper place, according to
    // the desired destination target version number
    val destPattern = "[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)" + publishVersion + "/[type]s/[artifact](-[classifier]).[ext]"
    theIvy.retrieve(caller, (ivyxmlDir / destPattern).getCanonicalPath, ro)
    def guessConfigurations(a: Artifact) = {
      val classifier = Option(a.getExtraAttributes().get("classifier").asInstanceOf[String])
      log.debug("Extra Attributes for " + a + " is " + a.getExtraAttributes)
      (a.getConfigurations() ++ (classifier match {
        case Some("sources") => Seq("sources")
        case Some("javadoc") => Seq("javadoc")
        // if someone asks for the default or compile configs, give them the jar
        case None if a.getType == "jar" && a.getExt == "jar" => Seq("compile", "default")
        case _ => Seq("default") // TODO: fetch configs better?
      })).distinct
    }

    //
    // generation of ivy.xml, which will complete our regenerated repository
    //
    val modRevIdpublish = ModuleRevisionId.newInstance(modRevId, publishVersion)
    val md2 = new DefaultModuleDescriptor(modRevIdpublish, "release", new java.util.Date())
    md2.addExtraAttributeNamespace("m", "http://ant.apache.org/ivy/maven")
    // the default config should not be necessary, but some projects rely on it being present (for instance org.scala-sbt#boot-scala;1.0)
    md2.addConfiguration(new Configuration("default", Configuration.Visibility.PUBLIC, "", Array[String](), false, null))
    firstNode.getAllArtifacts() foreach { a =>
      val configs = guessConfigurations(a)
      configs foreach { c =>
        md2.addConfiguration(new Configuration(c))
        md2.addArtifact(c, a) // it should not be necessary to patch the version here
        log.debug("Adding artifact: " + a + " in conf " + c)
      }
    }
    md2.addConfiguration(new Configuration("provided", Configuration.Visibility.PUBLIC, "", Array[String](), false, null))
    md2.addConfiguration(new Configuration("optional", Configuration.Visibility.PUBLIC, "", Array[String](), false, null))
    deps foreach {
      case PublishIvyInfo(d, rewritten, optional) =>
        val mrid = d.getModuleRevisionId()
        val depDesc = new DefaultDependencyDescriptor(md2,
          mrid, /*force*/ rewritten, /*changing*/ mrid.getRevision.endsWith("-SNAPSHOT"), /*transitive*/ true)
        log.debug("Adding dependency: " + d + ", which has extra attrs: " + d.getExtraAttributes()+" and configurations: " + d.getConfigurations.mkString(","))
        val art = new DefaultDependencyArtifactDescriptor(
          depDesc,
          d.getName,
          d.getType, d.getExt, null, d.getExtraAttributes)
        val dConfigs = guessConfigurations(d)
        dConfigs foreach { c =>
          log.debug("Config: " + c)
          art.addConfiguration(c)
          depDesc.addDependencyArtifact(c, art)
        }
        // TODO: the choice of configuration mappings may possibly need
        // some additional tweaking
        if (optional) {
          depDesc.addDependencyConfiguration("optional", "default(compile)")
        } else if (dConfigs contains "compile") {
          depDesc.addDependencyConfiguration("compile", "default(compile)")
          depDesc.addDependencyConfiguration("default", "default(compile)")
        }
        // no mapping for other dependencies, otherwise it would mean
        // for example that when I grab the source of this module, I also
        // grab the source dependencies from the dependencies,
        // which clearly doesn't make sense.
        // dConfigs.diff(Seq("compile")) foreach { c =>
        //  depDesc.addDependencyConfiguration(c, c)
        // }
        md2.addDependency(depDesc)
    }
    resolveOptions.setRefresh(true)
    val md2configs = md2.getConfigurations map (_.getName)
    if (md2configs contains "compile") {
      md2.addConfiguration(new Configuration("runtime", Configuration.Visibility.PUBLIC, "", Array[String]("compile"), true, null))
      md2.addConfiguration(new Configuration("test", Configuration.Visibility.PUBLIC, "", Array[String]("compile"), true, null))
    }
    theIvy.resolve(md2, resolveOptions)

    // TODO: there is always a reference to a main jar in the xml.
    // This is incorrect (but how to get rid of it?)
    val deo = new org.apache.ivy.core.deliver.DeliverOptions
    deo.setValidate(true)
    deo.setConfs(md2.getConfigurations() map { _.getName() })
    deo.setPubdate(new java.util.Date())
    theIvy.deliver(modRevIdpublish, modRevIdpublish.getRevision, (ivyxmlDir / ivyPattern).getCanonicalPath, deo)
    ivyxmlDir.***.get.foreach {
      f =>
        if (f.getName == "ivy.xml")
          scala.io.Source.fromFile(f).getLines foreach { s => log.debug(s) }
    }
  }

  // the stuff below is adapted from sbt's boot Ivy-related code, which is unfortunately nearly all marked private;
  // the only choice is replicating the necessary code here, with customizations.
  def isEmpty(line: String) = line.length == 0
  /** Uses the pattern defined in BuildConfiguration to download sbt from Google code.*/
  def urlResolver(id: String, base: String, ivyPattern: String, artifactPattern: String, mavenCompatible: Boolean) =
    {
      val resolver = new URLResolver
      resolver.setName(id)
      resolver.addIvyPattern(adjustPattern(base, ivyPattern))
      resolver.addArtifactPattern(adjustPattern(base, artifactPattern))
      resolver.setM2compatible(mavenCompatible)
      resolver
    }
  def adjustPattern(base: String, pattern: String): String =
    (if (base.endsWith("/") || isEmpty(base)) base else (base + "/")) + pattern
  def mavenLocal = mavenResolver("Maven2 Local", "file://" + System.getProperty("user.home") + "/.m2/repository/")
  /** Creates a maven-style resolver.*/
  def mavenResolver(name: String, root: String) =
    {
      val resolver = defaultMavenResolver(name)
      resolver.setRoot(root)
      resolver
    }
  /** Creates a resolver for Maven Central.*/
  def mavenMainResolver = defaultMavenResolver("Maven Central")
  /** Creates a maven-style resolver with the default root.*/
  def defaultMavenResolver(name: String) =
    {
      val resolver = new IBiblioResolver
      resolver.setName(name)
      resolver.setM2compatible(true)
      resolver
    }
  /** The name of the local Ivy repository, which is used when compiling sbt from source.*/
  val LocalIvyName = "local"
  /** The pattern used for the local Ivy repository, which is used when compiling sbt from source.*/
  val LocalPattern = "[organisation]/[module]/[revision]/[type]s/[artifact](-[classifier]).[ext]"
  /** The artifact pattern used for the local Ivy repository.*/
  def LocalArtifactPattern = LocalPattern
  /** The Ivy pattern used for the local Ivy repository.*/
  def LocalIvyPattern = LocalPattern
  def localResolver(ivyUserDirectory: String) =
    {
      val localIvyRoot = ivyUserDirectory + "/local"
      val resolver = new FileSystemResolver
      resolver.setName(LocalIvyName)
      resolver.addIvyPattern(localIvyRoot + "/" + LocalIvyPattern)
      resolver.addArtifactPattern(localIvyRoot + "/" + LocalArtifactPattern)
      resolver
    }
  val SnapshotPattern = java.util.regex.Pattern.compile("""(\d+).(\d+).(\d+)-(\d{8})\.(\d{6})-(\d+|\+)""")
  def scalaSnapshots(scalaVersion: String) =
    {
      val m = SnapshotPattern.matcher(scalaVersion)
      if (m.matches) {
        val base = List(1, 2, 3).map(m.group).mkString(".")
        val pattern = "https://oss.sonatype.org/content/repositories/snapshots/[organization]/[module]/" + base + "-SNAPSHOT/[artifact]-[revision](-[classifier]).[ext]"

        val resolver = new URLResolver
        resolver.setName("Sonatype OSS Snapshots")
        resolver.setM2compatible(true)
        resolver.addArtifactPattern(pattern)
        resolver
      } else
        mavenResolver("Sonatype Snapshots Repository", "https://oss.sonatype.org/content/repositories/snapshots")
    }

  def toIvy(repo: xsbti.Repository, ivyHome: File) = repo match {
    case m: xsbti.MavenRepository => mavenResolver(m.id, m.url.toString)
    case i: xsbti.IvyRepository => urlResolver(i.id, i.url.toString, i.ivyPattern, i.artifactPattern, i.mavenCompatible)
    case p: xsbti.PredefinedRepository => p.id match {
      // "local" is made to point to the same ivyHome, but nothing is ever published there
      case Local => localResolver(ivyHome.getAbsolutePath)
      case MavenLocal => mavenLocal
      case MavenCentral => mavenMainResolver
      case ScalaToolsReleases | SonatypeOSSReleases => mavenResolver("Sonatype Releases Repository", "https://oss.sonatype.org/content/repositories/releases")
      case ScalaToolsSnapshots | SonatypeOSSSnapshots => scalaSnapshots("")
    }
  }
  def hasImplicitClassifier(artifact: Artifact): Boolean =
    {
      import collection.JavaConversions._
      artifact.getQualifiedExtraAttributes.keys.exists(_.asInstanceOf[String] startsWith "m:")
    }
  def includeRepo(repo: xsbti.Repository) = !(isMavenLocal(repo))

  def localIvyRepoResolver(dbuildRepoDir: String) =
    {
      val resolver = new FileSystemResolver
      resolver.setName("dbuild-local-repo-ivy")
      resolver.addIvyPattern(dbuildRepoDir + "/" + LocalIvyPattern)
      resolver.addArtifactPattern(dbuildRepoDir + "/" + LocalArtifactPattern)
      resolver
    }

  def addResolvers(settings: IvySettings, ivyHome: File, repos: List[xsbti.Repository], dbuildRepoDir: File) {
    val newDefault = new ChainResolver {
      override def locate(artifact: Artifact) =
        if (hasImplicitClassifier(artifact)) null else super.locate(artifact)
    }
    newDefault.setName("redefined-public")
    newDefault.add(mavenResolver("dbuild-local-repo-maven", "file:" + dbuildRepoDir.getCanonicalPath()))
    newDefault.add(localIvyRepoResolver(dbuildRepoDir.getCanonicalPath))
    //    if (repos.isEmpty) sys.error("No repositories defined in ivy build system (internal error).")
    for (repo <- repos if includeRepo(repo)) {
      val ivyRepo = toIvy(repo, ivyHome)
      ivyRepo.setDescriptor(BasicResolver.DESCRIPTOR_REQUIRED)
      newDefault.add(ivyRepo)
    }
    settings.addResolver(newDefault)
    settings.setDefaultResolver(newDefault.getName)
  }
  def isMavenLocal(repo: xsbti.Repository) = repo match { case p: xsbti.PredefinedRepository => p.id == xsbti.Predefined.MavenLocal; case _ => false }

  object SbtIvyLogger {
    val IgnorePrefix = "impossible to define"
    val UnknownResolver = "unknown resolver"
    def acceptError(msg: String) = acceptMessage(msg) && !msg.startsWith(UnknownResolver)
    def acceptMessage(msg: String) = (msg ne null) && !msg.startsWith(IgnorePrefix)
  }

  final class IvyLoggerInterface(logger: Logger) extends ivy.util.MessageLogger {
    import SbtIvyLogger._
    def rawlog(msg: String, level: Int) = log(msg, level)
    def log(msg: String, level: Int) {
      import ivy.util.Message.{ MSG_DEBUG, MSG_VERBOSE, MSG_INFO, MSG_WARN, MSG_ERR }
      level match {
        case MSG_DEBUG => debug(msg)
        case MSG_VERBOSE => verbose(msg)
        case MSG_INFO => info(msg)
        case MSG_WARN => warn(msg)
        case MSG_ERR => error(msg)
      }
    }

    def debug(msg: String) {}
    def verbose(msg: String) = logger.verbose(msg)
    def deprecated(msg: String) = warn(msg)
    def info(msg: String) = logger.info(msg)
    def rawinfo(msg: String) = info(msg)
    def warn(msg: String) = logger.warn(msg)
    def error(msg: String) = if (acceptError(msg)) logger.error(msg)

    private def emptyList = java.util.Collections.emptyList[T forSome { type T }]
    def getProblems = emptyList
    def getWarns = emptyList
    def getErrors = emptyList

    def clearProblems = ()
    def sumupProblems = clearProblems()
    def progress = ()
    def endProgress = ()

    def endProgress(msg: String) = info(msg)
    def isShowProgress = false
    def setShowProgress(progress: Boolean) {}
  }

}