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
  private def ivyExpandConfig(config: ProjectBuildConfig) = config.extra match {
    case None => IvyExtraConfig() // pick default values
    case Some(ec: IvyExtraConfig) => ec
    case _ => throw new Exception("Internal error: ivy build config options are the wrong type in project \"" + config.name + "\". Please report")
  }

  // ivyxmlDir and deps are only used when creating the ivy.xml file, which only happens when
  // ivyxmlDir is Some(dir). 
  def operateIvy(config: ProjectBuildConfig, baseDir: File, repos: List[xsbti.Repository], log: Logger,
    transitive: Boolean = true, ivyxmlDir: Option[File] = None, deps: Seq[(Artifact,Boolean)]=Seq.empty): ResolveReport = {
    log.info("Running Ivy to extract project info: " + config.name)
    val extra = ivyExpandConfig(config)
    import extra._
    // this is the one local to the project (extraction or build)
    val ivyHome = (baseDir / ".ivy2")
    if (!config.uri.startsWith("ivy:"))
      sys.error("Fatal: the uri in Ivy project " + config.name + " does not start with \"ivy:\"")
    val module = config.uri.substring(4)
    log.debug("requested module is: " + module)
    val settings = new IvySettings()
    settings.setDefaultIvyUserDir(ivyHome)
    addResolvers(settings, ivyHome, repos)
    settings.setDefaultCacheArtifactPattern(ivyPattern)
    val cache = settings.getDefaultCache()
    val theIvy = Ivy.newInstance(settings)
    theIvy.getLoggerEngine.pushLogger(new IvyLoggerInterface(log))
    sbt.IO.withTemporaryFile("ivy", ".xml") { ivyFile =>
      val outer = ModuleRevisionId.newInstance("dbuild-ivy", "dbuild-ivy", "working")
      val md = DefaultModuleDescriptor.newDefaultInstance(outer)
      md.addExtraAttributeNamespace("m", "http://ant.apache.org/ivy/maven")
      val modRevId = ModuleRevisionId.parse(module)
      val dd = new DefaultDependencyDescriptor(md,
        modRevId, /*force*/ true, /*changing*/ modRevId.getRevision.endsWith("-SNAPSHOT"), /*transitive*/ transitive && mainJar)
      // if !mainJar and no other source/javadoc/classifier, will pick default artifact (usually the jar)

      def addArtifact(classifier: String, typ: String = "jar", ext: String = "jar", config: String = "default") = {
        val classif = new java.util.HashMap[String, String]()
        if (classifier != "") classif.put("m:classifier", classifier)
        val art = new DefaultDependencyArtifactDescriptor(
          dd,
          modRevId.getName,
          typ, ext, null, classif)
        art.addConfiguration(config)
        dd.addDependencyArtifact(config, art)
      }

      if (sources) addArtifact("sources", "src")
      if (javadoc) addArtifact("javadoc", "doc")
      if (mainJar) addArtifact("", "jar")
      artifacts foreach { a => addArtifact(a.classifier, a.typ, a.ext) }
      md.addDependency(dd)

      //creates an ivy configuration file
      XmlModuleDescriptorWriter.write(md, ivyFile)
      //scala.io.Source.fromFile(ivyFile).getLines foreach { s => log.debug(s) }
      val resolveOptions = new ResolveOptions().setConfs(Array("default"))
      resolveOptions.setLog(org.apache.ivy.core.LogOptions.LOG_DOWNLOAD_ONLY)
      val report: ResolveReport = theIvy.resolve(ivyFile.toURL(), resolveOptions)
      if (report.hasError) sys.error("Ivy resolution failure")

      import scala.collection.JavaConversions._
      val nodes = report.getDependencies().asInstanceOf[_root_.java.util.List[IvyNode]]
      if (nodes.isEmpty) {
        sys.error("Unexpected Ivy error: no nodes after resolve. Please report.")
      }
      val firstNode = nodes.get(0)
      if (!firstNode.isLoaded) {
        sys.error("Unexpected Ivy error: node not loaded (" + firstNode.getModuleId + ")")
      }

      
      // should we publish an ivy.xml? Do so if ivyxmlDir is not None
      ivyxmlDir match {
        case Some(dir) =>

          val ro = new org.apache.ivy.core.retrieve.RetrieveOptions
          ro.setLog(org.apache.ivy.core.LogOptions.LOG_DOWNLOAD_ONLY)
          ro.setConfs(Array("default"))
          theIvy.retrieve(outer, (dir / ivyPattern).getCanonicalPath, ro)

          val md2 = new DefaultModuleDescriptor(modRevId, "release", new java.util.Date())
          md2.addExtraAttributeNamespace("m", "http://ant.apache.org/ivy/maven")
          firstNode.getAllArtifacts() foreach { a =>
            val classifier = Option(a.getAttributes().get("classifier").asInstanceOf[String])
            val configs = (a.getConfigurations() :+ (classifier match {
              case Some("sources") => "sources"
              case Some("javadoc") => "javadoc"
              case _ => "default" // TODO: fetch configs better?
            })).distinct
            configs foreach { c =>
              md2.addConfiguration(new Configuration(c))
              md2.addArtifact(c, a)
            }
          }
          // needed? probably not.
          // md2.addConfiguration(new Configuration("provided",Configuration.Visibility.PUBLIC,"",Array[String](),false,null))
          deps foreach {
            case (d, rewritten) =>
            val mrid = d.getModuleRevisionId()
            val depDesc = new DefaultDependencyDescriptor(md2,
              mrid, /*force*/ rewritten, /*changing*/ mrid.getRevision.endsWith("-SNAPSHOT"), /*transitive*/ true)
            log.debug("Adding dependency: " + d)
            val art = new DefaultDependencyArtifactDescriptor(
              depDesc,
              d.getName,
              d.getType, d.getExt, null, d.getExtraAttributes)
            art.addConfiguration("default")
            depDesc.addDependencyArtifact("default", art)
            md2.addDependency(depDesc)
          }
          resolveOptions.setRefresh(true)
          resolveOptions.setConfs(md2.getConfigurations map(_.getName))
          theIvy.resolve(md2, resolveOptions)

          // TODO: there is always a reference to a main jar in the xml.
          // This is incorrect (but how to get rid of it?)
          val deo = new org.apache.ivy.core.deliver.DeliverOptions
          deo.setValidate(true)
          deo.setConfs(md2.getConfigurations() map {_.getName()})
          deo.setPubdate(new java.util.Date())
          theIvy.deliver(modRevId, modRevId.getRevision, (dir / ivyPattern).getCanonicalPath, deo)
          dir.***.get.foreach {
            f => if (f.getName=="ivy.xml")
              scala.io.Source.fromFile(f).getLines foreach { s => log.debug(s) }
          }
/*
          val pat = new java.util.LinkedList[String]()
          pat.add((cache / ivyPattern).getCanonicalPath)
          val po = new org.apache.ivy.core.publish.PublishOptions
          po.setOverwrite(true)
          po.setHaltOnMissing(true)
          po.setUpdate(true)
          po.getSrcIvyPattern(....)
          po.setExtraArtifacts(firstNode.getAllArtifacts)
          theIvy.publish(modRevId, pat, "local", po)
*/
        case None =>
      }

      report
    }
  }

  // the stuff below is adapted from sbt's Ivy-related code, which is unfortunately nearly all marked private;
  // the only choice is replicating the necessary code here.
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

  def addResolvers(settings: IvySettings, ivyHome: File, repos: List[xsbti.Repository]) {
    val newDefault = new ChainResolver {
      override def locate(artifact: Artifact) =
        if (hasImplicitClassifier(artifact)) null else super.locate(artifact)
    }
    newDefault.setName("redefined-public")
    if (repos.isEmpty) sys.error("No repositories defined in ivy build system (internal error).")
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