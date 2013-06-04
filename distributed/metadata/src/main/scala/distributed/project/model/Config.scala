package distributed.project.model

import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.lambdaworks.jacks._
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.annotation.JsonProperty


/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
@JsonDeserialize(using = classOf[BuildConfigDeserializer])
case class ProjectBuildConfig(name: String, 
    system: String = "sbt",
    uri: String,
  @JsonProperty("set-version")
    setVersion: Option[String],
    extra: Option[ExtraConfig]) {
  def uuid = hashing sha1 this
}

case class ProjectBuildConfigShadow(name: String, 
    system: String = "sbt",
    uri: String, 
  @JsonProperty("set-version")
    setVersion: Option[String],
    extra: JsonNode=null) {
}

/** The initial configuration for a build. */
case class DistributedBuildConfig(projects: Seq[ProjectBuildConfig])


/** Configuration used for SBT and other builds.
  */
@JsonSerialize(using = classOf[ExtraSerializer])
class ExtraConfig


class ExtraSerializer extends JsonSerializer[ExtraConfig] {
  override def serialize(value: ExtraConfig, g: JsonGenerator, p: SerializerProvider) {
    val cfg=p.getConfig()
    val tf=cfg.getTypeFactory()
    val jt=tf.constructType(value.getClass)
    ScalaTypeSig(cfg.getTypeFactory, jt) match {
      case Some(sts) if sts.isCaseClass =>
        // the "true" below is for options.caseClassSkipNulls
        (new CaseClassSerializer(jt, sts.annotatedAccessors, true)).serialize(value.asInstanceOf[Product],g,p)
      case _ => throw new Exception("Internal error while serializing build system config. Please report.")
    }
  }
}

class BuildConfigDeserializer extends JsonDeserializer[ProjectBuildConfig] {
  override def deserialize(p: JsonParser, ctx: DeserializationContext): ProjectBuildConfig = {
    val buildSystems=BuildSystemExtras.buildSystems
    
    val tf=ctx.getConfig.getTypeFactory()
    val d = ctx.findContextualValueDeserializer(tf.constructType(classOf[ProjectBuildConfigShadow]), null)
    val generic=d.deserialize(p, ctx).asInstanceOf[ProjectBuildConfigShadow]

    if (generic==null) throw new Exception("Cannot deserialize build configuration: no value found")

    val from=generic.extra
    val system=generic.system
    if (!(buildSystems.contains(system))) throw new Exception("Build system \""+system+"\" is unknown.")
    val newData = if (from==null) None else Some({
      val cls=buildSystems(system)
      val jp=from.traverse()
      jp.nextToken()
      cls.cast(ctx.findContextualValueDeserializer(tf.constructType(cls), null).deserialize(jp,ctx))
    })
    ProjectBuildConfig(generic.name,system,generic.uri,generic.setVersion,newData)
  }
}

case class ScalaExtraConfig extends ExtraConfig

case class MavenExtraConfig (
    directory: String = ""
) extends ExtraConfig

/** sbt-specific build parameters
 */
case class SbtExtraConfig (
    @JsonProperty("sbt-version")
      sbtVersion: String = "", // Note: empty version is interpreted as default, when the Build System extracts this bit
    directory: String = "",
    @JsonProperty("measure-performance")
      measurePerformance: Boolean = false,
    @JsonProperty("run-tests")
      runTests: Boolean = true,
    options: Seq[String] = Seq.empty,
    // before extraction or building, run these commands ("set" or others)
    commands: Seq[String] = Seq.empty,
    projects: Seq[String] = Seq.empty // if empty -> build all projects (default)
) extends ExtraConfig

object BuildSystemExtras {
  val buildSystems:Map[String,java.lang.Class[_ <: ExtraConfig]] = Map(
      "sbt"->classOf[SbtExtraConfig],
      "scala"->classOf[ScalaExtraConfig],
      "maven"->classOf[MavenExtraConfig])
}
