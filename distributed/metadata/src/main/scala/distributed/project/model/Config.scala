package distributed.project.model

import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.lambdaworks.jacks._
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ser.std.StringSerializer
import com.fasterxml.jackson.databind.ser.impl.StringArraySerializer

/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
@JsonDeserialize(using = classOf[BuildConfigDeserializer])
case class ProjectBuildConfig(name: String,
  system: String = "sbt",
  uri: String,
  @JsonProperty("set-version") setVersion: Option[String],
  extra: Option[ExtraConfig])

private case class ProjectBuildConfigShadow(name: String,
  system: String = "sbt",
  uri: String,
  @JsonProperty("set-version") setVersion: Option[String],
  extra: JsonNode = null)

/**
 * The initial dbuild configuration. The "build" section is a complete
 * specification of the actual build, while the "options" section contains
 * accessory tasks and options that do not affect the actual build, but do
 * affect other parts of the dbuild behavior.
 */
case class DBuildConfiguration(
  build: DistributedBuildConfig,
  options: GeneralOptions = GeneralOptions() // pick defaults if empty
  )

/**
 *  Some of the options within the DistributedBuildConfig may affect
 *  the extraction of all the projects (for example, the default sbt version).
 *  We pack a copy of the BuildOptions together with the ProjectBuildConfig,
 *  and pass it to extraction.
 */
case class ExtractionConfig(
    buildConfig:ProjectBuildConfig,
    buildOptions:BuildOptions) {
    def uuid = hashing sha1 this
}

/**
 * The configuration for a build. Include here every bit of information that
 * affects the actual build; the parts that do not affect the actual build,
 * and do not belong into the repeatable build configuration, go into the
 * GeneralOptions class instead.
 */
case class DistributedBuildConfig(projects: Seq[ProjectBuildConfig],
  options: Option[BuildOptions])

/**
 * General options for dbuild, that do not affect the actual build.
 */
case class GeneralOptions(deploy: Seq[DeployOptions] = Seq.empty,
  notifications: NotificationOptions = NotificationOptions())

/**
 * This class acts as a useful wrapper for parameters that are Seqs of Strings: it makes it
 * possible to specify a simple string whenever an array of strings is expected in the JSON file.
 * Quite handy, really.
 */
@JsonSerialize(using = classOf[SeqStringSerializer])
@JsonDeserialize(using = classOf[SeqStringDeserializer])
case class SeqString(s: Seq[String]) {
  // whenever I use a SeqString to apply map or foreach, the implicit
  // will kick in. However, when I try to print or use it as a string,
  // its method toString() will be called. This is not normally a problem
  // (we don't usually print Seq[String]s directly in user-facing code),
  // but, just in case:
  override def toString() = s.toString
}
class SeqStringSerializer extends JsonSerializer[SeqString] {
  override def serialize(value: SeqString, g: JsonGenerator, p: SerializerProvider) {
    value.s.length match {
      case 1 =>
        val vs = p.findValueSerializer(classOf[String], null)
        vs.serialize(value.s(0), g, p)
      case _ =>
        val vs = p.findValueSerializer(classOf[Array[String]], null)
        vs.serialize(value.s.toArray, g, p)
    }
  }
}
class SeqStringDeserializer extends JsonDeserializer[SeqString] {
  override def deserialize(p: JsonParser, ctx: DeserializationContext): SeqString = {
    val tf = ctx.getConfig.getTypeFactory()
    val d = ctx.findContextualValueDeserializer(tf.constructType(classOf[JsonNode]), null)
    val generic = d.deserialize(p, ctx).asInstanceOf[JsonNode]
    val jp = generic.traverse()
    jp.nextToken()
    def valueAs[T](cls: Class[T]) = {
      val vd = ctx.findContextualValueDeserializer(tf.constructType(cls), null)
      cls.cast(vd.deserialize(jp, ctx))
    }
    if (generic.isTextual()) {
      SeqString(Seq(valueAs(classOf[String])))
    } else {
      SeqString(valueAs(classOf[Array[String]]))
    }
  }
}
object SeqString {
  implicit def SeqToSeqString(s: Seq[String]): SeqString = SeqString(s)
  implicit def SeqStringToSeq(a: SeqString): Seq[String] = a.s
}

/** a generic options section that relies on a list of projects/subprojects */
abstract class ProjectBasedOptions {
  def projects: SeqSelectorElement

  /**
   * From its list of selected projects, which may include '.' for the root, and
   *  the BuildOutcome of the root, flattens the definition in order to select a
   *  subset of the root children.
   *  If '.' is present as a project, return the list of all the children.
   *  If '.' is present in a subproject definition, consider the list of
   *  subprojects as children (they are the subprojects of root, in a sense).
   *  Combine that list with the of remaining requested projects. If multiple
   *  project/subproject requests exist for the same project name, combine them together.
   *  NOTE: there is no assumption that the project names in the various request actually exist.
   *
   *  An auxiliary role of this method is that of performing a sanity check on
   *  the list of projects/subprojects, which is directly the list that
   *  the user wrote in the configuration file, and may contain errors.
   */
  def flattenAndCheckProjectList(allProjNames: Set[String]): Set[SelectorElement] = {
    def reqFromNames(n: Set[String]): Set[SelectorElement] = n map SelectorProject
    // let's split the requests by type
    val projReqs = projects.collect { case p: SelectorProject => p }.toSet
    val subProjReqs = projects.collect { case p: SelectorSubProjects => p }.toSet

    val fromRoot = if (projReqs.exists(_.name == ".")) allProjNames else Set[String]()
    // list of names of projects mentioned in subprojects from root
    val fromDotSubs = subProjReqs.filter(_.name == ".").flatMap { p: SelectorSubProjects => p.info.publish }
    // are you kidding me?
    if (fromDotSubs.contains(".")) sys.error("A from/publish defined '.' as a subproject of '.', which is impossible. Please amend.")
    // ok, this is the complete list of full project requests
    val allProjReqs: Set[SelectorElement] = reqFromNames(fromRoot) ++ reqFromNames(fromDotSubs) ++ projReqs.filterNot(_.name == ".")
    // remove the subproj requests that are already in the full proj set.
    val restSubProjReqs = subProjReqs.filterNot { p => allProjReqs.map { _.name }.contains(p.name) }
    // and now we flatten together those with the same 'from'
    val allSubProjReqsMap = restSubProjReqs.filterNot(_.name == ".").groupBy(_.name).toSet
    val allSubProjReq: Set[SelectorElement] = allSubProjReqsMap map {
      case (name, seq) => SelectorSubProjects(SubProjects(name, seq.map { _.info.publish }.flatten.toSeq))
    }
    val reqs = allSubProjReq ++ allProjReqs
    val unknown = reqs.map(_.name).diff(allProjNames)
    if (unknown.nonEmpty) sys.error(unknown.mkString("These project names are unknown: ", ",", ""))
    reqs
  }
}

/** Deploy information. */
case class DeployOptions(
  /** deploy target */
  uri: String,
  /** path to the credentials file */
  credentials: Option[String],
  /** names of the projects that should be deployed. Default: ".", meaning all */
  projects: SeqSelectorElement = Seq(SelectorProject(".")),
  /** signing options */
  sign: Option[DeploySignOptions]) extends ProjectBasedOptions
/** used to select subprojects from one project */
case class SubProjects(from: String, publish: SeqString)

/**
 * Signing options.
 *  secret-ring is the path to the file containing the pgp secret key ring. If not supplied, '~/.gnupg/secring.gpg' is used.
 *  id is the long key id (the whole 64 bits). If not supplied, the default master key is used.
 *  passphrase is the path to the file containing the passphrase; there is no interactive option.
 */
case class DeploySignOptions(
  @JsonProperty("secret-ring") secretRing: Option[String],
  id: Option[String],
  passphrase: String)

/**
 * Configuration used for SBT and other builds.
 */
@JsonSerialize(using = classOf[ExtraSerializer])
class ExtraConfig

class ExtraSerializer extends JsonSerializer[ExtraConfig] {
  override def serialize(value: ExtraConfig, g: JsonGenerator, p: SerializerProvider) {
    val cfg = p.getConfig()
    val tf = cfg.getTypeFactory()
    val jt = tf.constructType(value.getClass)
    ScalaTypeSig(cfg.getTypeFactory, jt) match {
      case Some(sts) if sts.isCaseClass =>
        // the "true" below is for options.caseClassSkipNulls
        (new CaseClassSerializer(jt, sts.annotatedAccessors, true)).serialize(value.asInstanceOf[Product], g, p)
      case _ => throw new Exception("Internal error while serializing build system config. Please report.")
    }
  }
}

class BuildConfigDeserializer extends JsonDeserializer[ProjectBuildConfig] {
  override def deserialize(p: JsonParser, ctx: DeserializationContext): ProjectBuildConfig = {
    val buildSystems = BuildSystemExtras.buildSystems

    val tf = ctx.getConfig.getTypeFactory()
    val d = ctx.findContextualValueDeserializer(tf.constructType(classOf[ProjectBuildConfigShadow]), null)
    val generic = d.deserialize(p, ctx).asInstanceOf[ProjectBuildConfigShadow]

    if (generic == null) throw new Exception("Cannot deserialize build configuration: no value found")

    val from = generic.extra
    val system = generic.system
    if (!(buildSystems.contains(system))) throw new Exception("Build system \"" + system + "\" is unknown.")
    val newData = if (from == null) None else Some({
      val cls = buildSystems(system)
      val jp = from.traverse()
      jp.nextToken()
      cls.cast(ctx.findContextualValueDeserializer(tf.constructType(cls), null).deserialize(jp, ctx))
    })
    ProjectBuildConfig(generic.name, system, generic.uri, generic.setVersion, newData)
  }
}
/**
 * The 'extra' options for the Scala build system are:
 * build-number:  Overwrites the standard build.number, with a custom number
 *                Note that set-version changes the jar artifact version number,
 *                while build-number changes the version that Scala reports, for
 *                example, in the REPL.
 * build-target:  Overrides the standard ant target that is invoked in order to
 *                generate the artifacts. The default is 'distpack-maven-opt', and it
 *                is not normally changed.
 * build-options: A sequence of additional options that will be passed to ant.
 *                They can specify properties, or modify in some other way the
 *                build. These options will be passed after the ones set by
 *                dbuild, on the command line.
 * All fields are optional.
 */
case class ScalaExtraConfig(
  @JsonProperty("build-number") buildNumber: Option[BuildNumber],
  @JsonProperty("build-target") buildTarget: Option[String],
  @JsonProperty("build-options") buildOptions: SeqString = Seq.empty,
  exclude: SeqString = Seq.empty // if empty -> exclude no projects (default)
  ) extends ExtraConfig

case class BuildNumber(major: String, minor: String, patch: String, bnum: String)

case class IvyExtraConfig(
  sources: Boolean = false,
  javadoc: Boolean = false,
  @JsonProperty("main-jar") mainJar: Boolean = true,
  artifacts: Seq[IvyArtifact] = Seq.empty,
  excludes: Seq[IvyExclude] = Seq.empty,
  // The snapshot marker is used internally by the Ivy build system
  // in order to distinguish among different snapshots of the same
  // dependency, in which case it contains the publication date.
  // Note: this field is not for use by end user.
  @JsonProperty("snapshot-marker") snapshotMarker: Option[String]) extends ExtraConfig

case class IvyArtifact(
  classifier: String = "",
  @JsonProperty("type") typ: String = "jar",
  ext: String = "jar",
  configs: SeqString = Seq("default"))

case class IvyExclude(
  organization: String,
  name: String
)
  
case class MavenExtraConfig(
  directory: String = "") extends ExtraConfig

/**
 * sbt-specific build parameters
 */
case class SbtExtraConfig(
  @JsonProperty("sbt-version") sbtVersion: String = "", // Note: empty version is interpreted as default, when the Build System extracts this bit
  directory: String = "",
  @JsonProperty("measure-performance") measurePerformance: Boolean = false,
  @JsonProperty("run-tests") runTests: Boolean = true,
  options: SeqString = Seq.empty,
  // before extraction or building, run these commands ("set" or others)
  commands: SeqString = Seq.empty,
  projects: SeqString = Seq.empty, // if empty -> build all projects (default)
  exclude: SeqString = Seq.empty // if empty -> exclude no projects (default)
  ) extends ExtraConfig

object BuildSystemExtras {
  val buildSystems: Map[String, java.lang.Class[_ <: ExtraConfig]] = Map(
    "sbt" -> classOf[SbtExtraConfig],
    "scala" -> classOf[ScalaExtraConfig],
    "ivy" -> classOf[IvyExtraConfig],
    "maven" -> classOf[MavenExtraConfig],
    "nil" -> classOf[NilExtraConfig])
}

/** configuration for the Nil build system */
case class NilExtraConfig(
  /** add the dependencies here, in some fashion to be decided */
  deps: SeqString = Seq.empty) extends ExtraConfig

// our simplified version of Either: we use it to group String and SelectorSubProjects in a transparent manner
@JsonSerialize(using = classOf[SelectorElementSerializer])
@JsonDeserialize(using = classOf[SelectorElementDeserializer])
sealed abstract class SelectorElement { def name: String }
case class SelectorProject(a: String) extends SelectorElement {
  override def toString() = a
  def name = a
}
case class SelectorSubProjects(info: SubProjects) extends SelectorElement {
  override def toString() = info.from + " " + info.publish.mkString("(", ",", ")")
  def name = info.from
}

class SelectorElementSerializer extends JsonSerializer[SelectorElement] {
  override def serialize(value: SelectorElement, g: JsonGenerator, p: SerializerProvider) {
    value match {
      case SelectorProject(s) =>
        new StringSerializer().serialize(s, g, p)
      case SelectorSubProjects(d) =>
        val cfg = p.getConfig()
        val tf = cfg.getTypeFactory()
        val jt = tf.constructType(classOf[SubProjects])
        val Some(sts) = ScalaTypeSig(cfg.getTypeFactory, jt)
        // the "true" below is for options.caseClassSkipNulls
        (new CaseClassSerializer(jt, sts.annotatedAccessors, true)).serialize(d, g, p)
      case _ => throw new Exception("Internal error while serializing deploy projects. Please report.")
    }
  }
}
class SelectorElementDeserializer extends JsonDeserializer[SelectorElement] {
  override def deserialize(p: JsonParser, ctx: DeserializationContext): SelectorElement = {
    val tf = ctx.getConfig.getTypeFactory()
    val d = ctx.findContextualValueDeserializer(tf.constructType(classOf[JsonNode]), null)
    val generic = d.deserialize(p, ctx).asInstanceOf[JsonNode]
    val jp = generic.traverse()
    jp.nextToken()
    def valueAs[T](cls: Class[T]) = cls.cast(ctx.findContextualValueDeserializer(tf.constructType(cls), null).deserialize(jp, ctx))
    if (generic.isTextual()) {
      SelectorProject(valueAs(classOf[String]))
    } else {
      SelectorSubProjects(valueAs(classOf[SubProjects]))
    }
  }
}

/**
 * same as SeqString, for Seq[SelectorElement]: a lonely String or a lonely
 *  SelectorSubProjs can also be used when a Seq[SelectorElement] is requested.
 */
@JsonSerialize(using = classOf[SeqElementSerializer])
@JsonDeserialize(using = classOf[SeqElementDeserializer])
case class SeqSelectorElement(s: Seq[SelectorElement])
class SeqElementSerializer extends JsonSerializer[SeqSelectorElement] {
  override def serialize(value: SeqSelectorElement, g: JsonGenerator, p: SerializerProvider) {
    value.s.length match {
      case 1 =>
        val vs = p.findValueSerializer(classOf[SelectorElement], null)
        vs.serialize(value.s(0), g, p)
      case _ =>
        val vs = p.findValueSerializer(classOf[Array[SelectorElement]], null)
        vs.serialize(value.s.toArray, g, p)
    }
  }
}
class SeqElementDeserializer extends JsonDeserializer[SeqSelectorElement] {
  override def deserialize(p: JsonParser, ctx: DeserializationContext): SeqSelectorElement = {
    val tf = ctx.getConfig.getTypeFactory()
    val d = ctx.findContextualValueDeserializer(tf.constructType(classOf[JsonNode]), null)
    val generic = d.deserialize(p, ctx).asInstanceOf[JsonNode]
    val jp = generic.traverse()
    jp.nextToken()
    def valueAs[T](cls: Class[T]) = {
      val vd = ctx.findContextualValueDeserializer(tf.constructType(cls), null)
      cls.cast(vd.deserialize(jp, ctx))
    }
    if (generic.isTextual() || generic.isObject()) {
      SeqSelectorElement(Seq(valueAs(classOf[SelectorElement])))
    } else { // Array, or something unexpected that will be caught later
      SeqSelectorElement(valueAs(classOf[Array[SelectorElement]]))
    }
  }
}
object SeqSelectorElement {
  implicit def SeqToSeqSelectorElement(s: Seq[SelectorElement]): SeqSelectorElement = SeqSelectorElement(s)
  implicit def SeqSelectorElementToSeq(a: SeqSelectorElement): Seq[SelectorElement] = a.s
}
/**
 * same as SeqString, for Seq[Notification]: a single Notification will be wrapped into an array.
 *  Note that I cannot make easily a generic version, since Jackson requires a no-args constructur
 *  and I cannot construct a sequence without a Manifest. So I only special case a few of them.
 */
@JsonSerialize(using = classOf[SeqNotificationSerializer])
@JsonDeserialize(using = classOf[SeqNotificationDeserializer])
case class SeqNotification(s: Seq[Notification])
class SeqNotificationSerializer extends JsonSerializer[SeqNotification] {
  override def serialize(value: SeqNotification, g: JsonGenerator, p: SerializerProvider) {
    value.s.length match {
      case 1 =>
        val vs = p.findValueSerializer(classOf[Notification], null)
        vs.serialize(value.s(0), g, p)
      case _ =>
        val vs = p.findValueSerializer(classOf[Array[Notification]], null)
        vs.serialize(value.s.toArray, g, p)
    }
  }
}
class SeqNotificationDeserializer extends JsonDeserializer[SeqNotification] {
  override def deserialize(p: JsonParser, ctx: DeserializationContext): SeqNotification = {
    val tf = ctx.getConfig.getTypeFactory()
    val d = ctx.findContextualValueDeserializer(tf.constructType(classOf[JsonNode]), null)
    val generic = d.deserialize(p, ctx).asInstanceOf[JsonNode]
    val jp = generic.traverse()
    jp.nextToken()
    def valueAs[T](cls: Class[T]) = {
      val vd = ctx.findContextualValueDeserializer(tf.constructType(cls), null)
      cls.cast(vd.deserialize(jp, ctx))
    }
    if (generic.isTextual() || generic.isObject()) {
      SeqNotification(Seq(valueAs(classOf[Notification])))
    } else { // Array, or something unexpected that will be caught later
      SeqNotification(valueAs(classOf[Array[Notification]]))
    }
  }
}
object SeqNotification {
  implicit def SeqToSeqNotification(s: Seq[Notification]): SeqNotification = SeqNotification(s)
  implicit def SeqNotificationToSeq(a: SeqNotification): Seq[Notification] = a.s
}

/**
 * These are options that affect all of the projects, but must not affect extraction:
 * extraction fully relies on the fact that the project is fully described by the
 * ProjectBuildConfig record.
 * Conversely, these options can affect the building stage; a copy of the record is
 * included in the RepeatableDistributedBuild, and is then included in each RepeatableProjectBuild
 * obtained from the repeatableBuilds within the RepeatableDistributedBuild.
 * Therefore *ONLY* place in this section the global options that affect the repeatebility of the
 * builds!! Place other global options elsewhere, in other top-level sections. Similarly, do no place
 * options that do not impact on the repeatability of the build inside the projects section; instead,
 * place them in a separate section, specifying the list of projects to which they apply (like deploy
 * and notifications).
 *
 * This section contains the option "cross-version, which controls the
 * crossVersion and scalaBinaryVersion sbt flags. It can have the following values:
 *   - "disabled" (default): All cross-version suffixes will be disabled, and each project
 *     will be published with just a dbuild-specific version suffix (unless "set-version" is used).
 *     However, the library dependencies that refer to Scala projects that are not included in this build
 *     configuration, and that have "binary" or "full" CrossVersion will have their scala version set to
 *     the full scala version string: as a result, missing dependent projects will be detected.
 *   - "standard": Each project will compile with its own suffix (typically _2.10 for 2.10.x, for example).
 *     Further, library dependencies that refer to Scala projects that are not included in this build
 *     configuration will not be rewritten: they might end up being fetched from Maven if a compatible
 *     version is found.
 *     This settings must be used when releasing, typically in conjunction with "set-version", in order
 *     to make sure cross-versioning works as it would in the original projects.
 *   - "full": Similar in concept to "disabled", except the all the sbt projects are changed so that
 *     the full Scala version string is used as a cross-version suffix (even those that would normally
 *     have cross-version disabled). Missing dependent projects will be detected.
 *   - "binaryFull": It is a bit of a hybrid between standard and full. This option will cause
 *     the projects that would normally publish with a binary suffix (like "_2.10") to publish using the
 *     full scala version string instead. The projects that have cross building disabled, however, will be
 *     unaffected. Missing dependent projects will be detected. This configuration is for testing only.
 *
 * In practice, do not include an "options" section at all in normal use, and just add "{cross-version:standard}"
 * if you are planning to release using "set-version".
 * 
 * This section also contains the sbt version that should be used by default (unless overridden in the individual
 * projects) to compile all the projects. If not specified, the string "0.12.4" is used.
 */
case class BuildOptions(
  @JsonProperty("cross-version") crossVersion: String = "disabled",
  // NEVER CHANGE the "0.12.4" below: the default of default will remain 0.12.4
  // also in the future (for repeatability); if the user wants a default of 0.13.0,
  // they can specify "build.options.sbt-version = 0.13.0"
  @JsonProperty("sbt-version") sbtVersion: String = "0.12.4")

/**
 * This section is used to notify users, by using some notification system.
 */
case class NotificationOptions(
  templates: Seq[NotificationTemplate] = Seq.empty,
  send: SeqNotification = Seq(Notification(kind = "console", send = None, when = Seq("always"))),
  /**
   * This section optionally contains defaults to be used for the various notification kinds.
   *  The values specified in the defaults section will be used for that kind if no value
   *  has been specified in a "send" record of that kind. Since the defaults of the defaults are
   *  by default the defaults of the notifications (since they use the same Notification record),
   *  unspecified fields in the defaults cause no change to the default interpretation of send
   *  records.
   */
  default: SeqNotification = Seq[Notification]())
/**
 *  A notification template; for notification systems that require short messages,
 *  use only the subject line. It is a template because variable
 *  substitution may occur before printing.
 *  It can have three components:
 *  1) A summary (<50 characters), with a short message of what went wrong.
 *     It is required, and is suitable, for instance, for a short console report
 *     or as an email subject line.
 *  2) A slightly longer short summary (<110 characters), suitable for SMS, Tweets, etc.
 *     It should be self-contained in terms of information. Defaults to the short summary.
 *  3) A long body with a more complete description. Defaults to the short message.
 *  Do not terminate it with a \n, as one will be added by the notification system if
 *  required in that specific case.
 *  An Id is also present, and is used to match against the (optional) template
 *  requested in the notification. 
 */
case class NotificationTemplate(
  id: String,
  summary: String,
  short: Option[String] = None,
  long: Option[String] = None)

/**
 * The NotificationTemplate is first resolved against the notification,
 * obtaining a ResolvedTemplate, then expanded by a formatter, obtaining
 * a TemplateFormatter, which can be used by the send() routine.
 */
case class ResolvedTemplate(
  id: String,
  summary: String,
  short: String,
  long: String)

@JsonDeserialize(using = classOf[NotificationDeserializer])
case class Notification(
  /** the kind of notification. Default is "email" */
  kind: String = "email",
  /**
   * kind-specific arguments. Optional, but some
   *  notification kinds (notably email) may require it.
   */
  send: Option[NotificationKind],
  /**
   * One of these IDs must match one of the BuildOutcome
   *  IDs for the notification to be sent. The default is
   *  when = [bad,success], which will send a message on every
   *  failure, and on the first success whenever there
   *  is a change in code or dependencies.
   */
  when: SeqString = Seq("bad", "success"),
  /** if None, default to the one from the outcome */
  template: Option[String] = None,
  /**
   * Names of the projects relevant to this notification.
   *  Default: ".", meaning that a notification will be
   *  sent with the status for the root build. If multiple
   *  projects are listed, a report will be sent for each
   *  of the projects (to the same recipient) (if the 'when'
   *  selector applies); that may be of use if a single recipient
   *  is used for two or more projects that do not depend on
   *  one another.
   *  dbuild is able to build a list automatically
   *  if a single string is specified.
   */
  projects: SeqSelectorElement = Seq(SelectorProject("."))) extends ProjectBasedOptions {
  /*
 *  example:
  
  options.notifications.send = [{
    projects: jline
    send.to: "antonio.cunei@typesafe.com"
  },{
    projects: scala-compiler
    send.to: "joshua.suereth@typesafe.com"
  }]
  options.notifications.defaults.send.smtp.server = "relay.typesafe.com"
  
*/
  /**
   * If the notification refers to a specific template name, use that template name,
   * otherwise the template name we need is the same as the notification kind.
   * Once we determined that, we search for it in the list of templates, passed as an
   * argument. Once we find it, we replace the missing parts of the template using
   * the appropriate defaults, and return the resolved template.
   */
  def resolveTemplate(definedTemplates: Seq[NotificationTemplate]): ResolvedTemplate = {
    val templName= template match {
      case None => kind
      case Some(t) => t
    }
    val templ = definedTemplates.find(_.id == templName) getOrElse sys.error("The requested notification template \"" + templName + "\" was not found.")
    val short = templ.short match {
      case Some(s) => s
      case None => templ.summary
    }
    val long = templ.long match {
      case Some(l) => l
      case None => short
    }
    ResolvedTemplate(templ.id, templ.summary, short, long)
  }
}
// We need this shadow class for serialization/deserialization to work
// It must be kept in sync with Notification.
private case class NotificationShadow(
  kind: String = "email",
  send: JsonNode = null,
  when: SeqString = Seq("bad", "success"),
  template: Option[String] = None,
  projects: SeqSelectorElement = Seq(SelectorProject(".")))

/**
 * The descriptor of options for each notification mechanism;
 * subclasses are ConsoleNotification, EmailNotification, etc.
 */
@JsonSerialize(using = classOf[NotificationKindSerializer])
abstract class NotificationKind

class NotificationKindSerializer extends JsonSerializer[NotificationKind] {
  override def serialize(value: NotificationKind, g: JsonGenerator, p: SerializerProvider) {
    val cfg = p.getConfig()
    val tf = cfg.getTypeFactory()
    val jt = tf.constructType(value.getClass)
    ScalaTypeSig(cfg.getTypeFactory, jt) match {
      case Some(sts) if sts.isCaseClass =>
        // the "true" below is for options.caseClassSkipNulls
        (new CaseClassSerializer(jt, sts.annotatedAccessors, true)).serialize(value.asInstanceOf[Product], g, p)
      case _ => throw new Exception("Internal error while serializing NotificationKind. Please report.")
    }
  }
}

class NotificationDeserializer extends JsonDeserializer[Notification] {
  override def deserialize(p: JsonParser, ctx: DeserializationContext): Notification = {
    val notificationKinds = NotificationKind.kinds

    val tf = ctx.getConfig.getTypeFactory()
    val d = ctx.findContextualValueDeserializer(tf.constructType(classOf[NotificationShadow]), null)
    val generic = d.deserialize(p, ctx).asInstanceOf[NotificationShadow]

    if (generic == null) throw new Exception("Cannot deserialize notification: no value found")

    val from = generic.send
    val kind = generic.kind

    if (!(notificationKinds.contains(kind))) throw new Exception("Notification kind \"" + kind + "\" is unknown.")
    val newData = if (from == null) None else Some({
      val cls = notificationKinds(kind)
      val jp = from.traverse()
      jp.nextToken()
      cls.cast(ctx.findContextualValueDeserializer(tf.constructType(cls), null).deserialize(jp, ctx))
    })
    Notification(kind, newData, generic.when, generic.template, generic.projects)
  }
}

/**
 * We could embed send() into NotificationKind, but:
 * 1) too much logic would end up in the low-level metadata package
 * 2) we would not have access to higher-level context
 * Therefore the actual send() is implemented in a twin class higher up in dbuild.
 * We also offer an opportunity to the NotificationKinds to do something once before all the
 * notifications are sent, and something afterward. Only the notification kinds that
 * are actually used in the configuration file will be called.
 */
abstract class NotificationContext[T <: NotificationKind](implicit m: Manifest[T]) {
  /** before() is called once after the build, before all the send()s of this kind. */
  def before() = {}
  /** after() is called after all the send()s of this kind, for cleanup. */
  def after() = {}
  /**
   * Send the notification using the template templ (do not use the one from outcome when implementing).
   *  If the notification fails, just throw an Exception.
   */
  protected def send(n: T, templ: TemplateFormatter, outcome: BuildOutcome): Unit
  /**
   * The NotificationKind record (identified by the label 'send' in the notification record)
   * is optional; if the user does not specify it, some default is necessary.
   * If there is not acceptable default, this method can throw an exception or otherwise
   * issue a message and abort.
   */
  protected def defaultOptions: T
  /**
   *  Merges two records; if anything in "over" was changed with respect to defaultOptions,
   *  then take the value of "over". Else, get the value from "under". If both are None,
   *  return defaultOptions.
   */
  protected def mergeOptions(over: T, under: T): T

  /**
   * The client code calls notify(), which redispatches to send(), implemented in subclasses.
   */
  def notify(n: Option[NotificationKind], templ: TemplateFormatter, outcome: BuildOutcome) = {
    n match {
      case None => send(defaultOptions, templ, outcome)
      case Some(no) =>
        // NotificationKinds are referred to by String IDs, so we have to check manually
        // (the code must work on 2.9 as well)
        if (m.erasure.isInstance(no)) send(no.asInstanceOf[T], templ, outcome) else
          sys.error("Internal error: " + this.getClass.getName + " received a " + n.getClass.getName + ". Please report.")
    }
  }
  /** The client code calls mergeOptionsK, which is internally re-dispatched to mergeOptions */
  def mergeOptionsK(over: Option[NotificationKind], under: Option[NotificationKind]): NotificationKind = {
    // Once again, I get something that is no more specific than a NotificationKind, but I *know* from a String ID
    // that they are the right kind for this Context, so I test for extra safety and cast. I am sure there is a
    // prettier solution, probably using type members or path-dependent types (by nesting NotificationKind and/or
    // NotificationContext into Notification), but implementation time is short at the moment and for the moment this will do.
    // TODO: clean up this uglyness
    (over, under) match {
      case (None, None) => defaultOptions
      case (Some(ov), None) => ov
      case (None, Some(un)) => un
      case (Some(ov), Some(un)) =>
        if (m.erasure.isInstance(ov) && m.erasure.isInstance(un)) mergeOptions(ov.asInstanceOf[T], un.asInstanceOf[T]) else
          sys.error("Internal error: " + this.getClass.getName + " received: " + ov.getClass.getName + "-" + un.getClass.getName + ". Please report.")
    }
  }

}

/**
 * All the addresses can be in standard RFC 822 format, either "here@there.com", or
 * "Hello Myself <here@there.com>".
 */
case class EmailNotification(
  to: SeqString = Seq.empty,
  cc: SeqString = Seq.empty,
  bcc: SeqString = Seq.empty,
  /**
   * The default sender is the account under which dbuild
   * is running right now (user@hostname). Else, specify it here.
   */
  from: Option[String] = None,
  /**
   * SMTP parameters. If not specified, messages will be sent
   * to localhost, port 25, no auth, hoping for the best.
   */
  smtp: Smtp = Smtp("localhost", None, "none", false)) extends NotificationKind

/**
 * Description of the smtp server to be used for email delivery.
 */
case class Smtp(
  /**
   * Specify here the smtp gateway that should be used.
   */
  server: String,
  /**
   * If your smtp server needs username/password, specify
   *  them in a file and supply the filename here. In the
   *  properties file you will need: user, host, password.
   *  The "host" property is the hostname you are connecting to,
   *  and must match the smtp server name. The "user" property
   *  is the name used during the authentication; it can be
   *  "name", or "name@somehost", depending on the providers.
   */
  credentials: Option[String] = None,
  /**
   * Set this to the desired authentication mechanism. It can be
   * starttls, ssl, submission (port 587/STARTTLS), or none. Default is ssl.
   */
  encryption: String = "ssl",
  /**
   * If using SSL/TLS, a self-signed certificate could be in use.
   * In that case, explicitly disable certificate checking here.
   */
  @JsonProperty("check-certificate") checkCertificate: Boolean = true)

case class ConsoleNotification() extends NotificationKind

object NotificationKind {
  val kinds: Map[String, java.lang.Class[_ <: NotificationKind]] = Map(
    "console" -> classOf[ConsoleNotification],
    "email" -> classOf[EmailNotification])
}
