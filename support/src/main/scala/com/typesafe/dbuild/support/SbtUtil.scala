package com.typesafe.dbuild.support
import com.typesafe.dbuild.model.SbtPluginAttrs
import _root_.sbt.{ ModuleID => SbtModuleID, Artifact => SbtArtifact }
import org.apache.ivy.core.module.id.{ ModuleRevisionId => IvyModuleRevisionId }
import _root_.scala.collection.JavaConversions._
import _root_.java.util.{ Map => JavaMap }

object SbtUtil {
  /**
   * For plugins, extract from the various representations of a module or
   * an artifact the two extra attributes that identify an sbt plugin. 
   */
  def pluginAttrs(m: SbtModuleID): Option[SbtPluginAttrs] =
    pluginAttrs(m.extraAttributes)
  def pluginAttrs(m: SbtArtifact): Option[SbtPluginAttrs] =
    pluginAttrs(m.extraAttributes)
  def pluginAttrs(mr: IvyModuleRevisionId): Option[SbtPluginAttrs] =
    pluginAttrs(mr.getExtraAttributes.asInstanceOf[JavaMap[String, String]].toMap)
  def pluginAttrs(attrs: Map[String, String]): Option[SbtPluginAttrs] = {
    if (attrs.contains("e:sbtVersion") && attrs.contains("e:scalaVersion"))
      Some(SbtPluginAttrs(attrs("e:sbtVersion"), attrs("e:scalaVersion")))
    else None
  }

  /**
   * Adapt an extraAttributes map, replacing (if present) the attributes that are
   * specific to an sbt plugin with the new supplied ones.
   */
  def fixAttrs(original: Map[String, String], newAttrs: Option[SbtPluginAttrs]) = newAttrs match {
    case None => original
    case Some(SbtPluginAttrs(sbtVersion, scalaVersion)) => original map {
      case (key, value) =>
        key -> (key match {
          case "e:sbtVersion" => sbtVersion
          case "e:scalaVersion" => scalaVersion
          case _ => value
        })
    }
  }
}
