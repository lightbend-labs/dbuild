package com.typesafe.dbuild.plugin

import sbt._
import com.typesafe.dbuild.support.NameFixer

object PluginFixins {
  case class PluginId(group: String, name: String)
  
  val pluginVersions = Map(
    PluginId("com.typesafe.sbtscalariform", "sbtscalariform") -> "0.5.0-SNAPSHOT",
    PluginId("com.typesafe.sbteclipse", "sbteclipse") -> "2.1.0-SNAPSHOT"
  )
  
  def fixPlugins(state: State): State = {
    val extracted = Project.extract(state)
    import extracted._
    
    state
  }
  
  def fixLibraryDependencies(s: Setting[_]): Setting[_] = 
    s.asInstanceOf[Setting[Seq[ModuleID]]] mapInit { (_, old) =>        
      old map fixPlugin
  }
  
     
  def fixPlugin(m: ModuleID): ModuleID = {
    val id = PluginId(m.organization, NameFixer.fixName(m.name))
    val optVersion = pluginVersions get id
    optVersion map (v => m.withRevision(v)) getOrElse m
  }
  
}
