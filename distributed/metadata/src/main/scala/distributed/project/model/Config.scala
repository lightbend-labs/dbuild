package distributed.project.model

import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.lambdaworks.jacks._
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ser.std.StringSerializer

/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
@JsonDeserialize(using = classOf[BuildConfigDeserializer])
case class ProjectBuildConfig(name: String,
  system: String = "sbt",
  uri: String,
  @JsonProperty("set-version") setVersion: Option[String],
  extra: Option[ExtraConfig]) {
  def uuid = hashing sha1 this
}

case class ProjectBuildConfigShadow(name: String,
  system: String = "sbt",
  uri: String,
  @JsonProperty("set-version") setVersion: Option[String],
  extra: JsonNode = null)

/** The initial configuration for a build. */
case class DistributedBuildConfig(projects: Seq[ProjectBuildConfig], deploy: Option[Seq[DeployOptions]])

/** Deploy information. */
case class DeployOptions(uri: String, // deploy target
  credentials: Option[String], // path to the credentials file
  projects: Option[Seq[DeployElement]], // names of the projects that should be deployed
  sign: Option[DeploySignOptions] // signing options
  )
case class DeploySubProjects(from: String, publish: Seq[String])

/** Signing options.
 *  secret-ring is the path to the file containing the pgp secret key ring. If not supplied, '~/.gnupg/secring.gpg' is used.
 *  id is the long key id (the whole 64 bits). If not supplied, the default master key is used.
 *  passphrase is the path to the file containing the passphrase; there is no interactive option. 
 */
case class DeploySignOptions(
  @JsonProperty("secret-ring") secretRing: Option[String],
  id: Option[String],
  passphrase: String
  )

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
  @JsonProperty("build-options") buildOptions: Seq[String] = Seq.empty,
  exclude: Seq[String] = Seq.empty // if empty -> exclude no projects (default)
  ) extends ExtraConfig

case class BuildNumber(major:String,minor:String,patch:String,bnum:String)

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
  options: Seq[String] = Seq.empty,
  // before extraction or building, run these commands ("set" or others)
  commands: Seq[String] = Seq.empty,
  projects: Seq[String] = Seq.empty, // if empty -> build all projects (default)
  exclude: Seq[String] = Seq.empty // if empty -> exclude no projects (default)
  ) extends ExtraConfig

object BuildSystemExtras {
  val buildSystems: Map[String, java.lang.Class[_ <: ExtraConfig]] = Map(
    "sbt" -> classOf[SbtExtraConfig],
    "scala" -> classOf[ScalaExtraConfig],
    "maven" -> classOf[MavenExtraConfig])
}

// our simplified version of Either: we use it to group String and DeploySubProjects in a transparent manner
@JsonSerialize(using = classOf[DeployElementSerializer])
@JsonDeserialize(using = classOf[DeployElementDeserializer])
sealed abstract class DeployElement { def name: String }
case class DeployElementProject(a: String) extends DeployElement {
  override def toString() = a
  def name = a
}
case class DeployElementSubProject(info: DeploySubProjects) extends DeployElement {
  override def toString() = info.from+" "+info.publish.mkString("(",",",")")
  def name = info.from
}

class DeployElementSerializer extends JsonSerializer[DeployElement] {
  override def serialize(value: DeployElement, g: JsonGenerator, p: SerializerProvider) {
    value match {
      case DeployElementProject(s) =>
        new StringSerializer().serialize(s, g, p)
      case DeployElementSubProject(d) =>
        val cfg = p.getConfig()
        val tf = cfg.getTypeFactory()
        val jt = tf.constructType(classOf[DeploySubProjects])
        val Some(sts) = ScalaTypeSig(cfg.getTypeFactory, jt)
        // the "true" below is for options.caseClassSkipNulls
        (new CaseClassSerializer(jt, sts.annotatedAccessors, true)).serialize(d, g, p)
      case _ => throw new Exception("Internal error while serializing deploy projects. Please report.")
    }
  }
}
class DeployElementDeserializer extends JsonDeserializer[DeployElement] {
  override def deserialize(p: JsonParser, ctx: DeserializationContext): DeployElement = {
    val tf = ctx.getConfig.getTypeFactory()
    val d = ctx.findContextualValueDeserializer(tf.constructType(classOf[JsonNode]), null)
    val generic = d.deserialize(p, ctx).asInstanceOf[JsonNode]
    val jp = generic.traverse()
    jp.nextToken()
    def valueAs[T](cls:Class[T])=cls.cast(ctx.findContextualValueDeserializer(tf.constructType(cls), null).deserialize(jp, ctx))
    if (generic.isTextual()) {
      DeployElementProject(valueAs(classOf[String]))
    } else {
      DeployElementSubProject(valueAs(classOf[DeploySubProjects]))
    }
  }
}

