package com.typesafe.sbt.distributed
package meta

import graph._
case class BuildNode(value: Build) extends Node[Build] {
  def hasProject(dep: ProjectDep): Boolean = {
    def isDep(p: Project): Boolean =
      (dep.organization == p.organization) && (dep.name == p.name)
    value.extracted.projects exists isDep
  }
}

class BuildGraph(builds: Seq[Build]) extends Graph[Build, Nothing] {
  private val buildNodes = builds.map(b => new BuildNode(b))(collection.breakOut)
  override val nodes: Set[Nd] = buildNodes.toSet
  // Memoized ok?
  def edges(n: Nd): Seq[Ed] = edgeMap get n getOrElse Seq.empty
  private val edgeMap: Map[Nd, Seq[Ed]] = 
    buildNodes.map(n => n -> edgesImpl(n))(collection.breakOut)
  private def edgesImpl(n: Nd): Seq[Ed] = (for {
    p <- n.value.extracted.projects
    d <- p.dependencies
    m <- buildNodes
    if (m != n) && (m hasProject d)  
  } yield EmptyEdge(n, m)).toSet.toSeq
}
