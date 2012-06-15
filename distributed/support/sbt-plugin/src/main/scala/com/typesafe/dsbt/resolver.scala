package com.typesafe.dsbt

import sbt.RawRepository
import org.apache.ivy.core.module.descriptor.{
  Artifact,
  DependencyDescriptor
}
import org.apache.ivy.core.report.{
  ArtifactDownloadReport,
  DownloadReport,
  DownloadStatus
}
import org.apache.ivy.core.resolve.{
  DownloadOptions,
  ResolveData,
  ResolvedModuleRevision
}
import org.apache.ivy.plugins.resolver.{
  AbstractResolver,
  DependencyResolver
}
import org.apache.ivy.plugins.resolver.util.ResolvedResource

import distributed.project.model
import distributed.project.BuildResultFileParser


object DistributedDependencyResolver {
  def apply() = new RawRepository(new DistributedDependencyResolver(loadConfig))
  def depFile = Option(System.getProperty("project.build.deps.file")) map (new java.io.File(_))
  def loadConfig: model.BuildArtifacts =
    (depFile flatMap BuildResultFileParser.parseMetaFile 
        getOrElse model.BuildArtifacts(Seq.empty))
}

/** A dependency resolver that should pull dependencies from recently
 * built distributed projects.
 */
class DistributedDependencyResolver(config: model.BuildArtifacts) extends AbstractResolver with DependencyResolver {
  override def publish(artifact: Artifact, src: java.io.File, overwrite: Boolean): Unit = ()
  
  
  override def download(artifacts: Array[Artifact], options: DownloadOptions): DownloadReport = {
    val d = new DownloadReport
    for {
      a <- artifacts      
    } d addArtifactReport downloadArtifact(a, options)
    d
  } 
  
  private def downloadArtifact(artifact: Artifact, options: DownloadOptions): ArtifactDownloadReport = {
    val report = new ArtifactDownloadReport(artifact)
    report setDownloadTimeMillis 0L
    findLocalArtifact(artifact) match {
      case Some(file) =>
        report setDownloadStatus DownloadStatus.SUCCESSFUL
        report setLocalFile file
        report setDownloadDetails "Cached build dependency."
      case None =>
      	report setDownloadStatus DownloadStatus.FAILED
      	report setDownloadDetails "Not found."
    }
    report
  }
  
  def findLocalArtifact(artifact: Artifact): Option[java.io.File] = (
      for {
        a <- config.artifacts
        if artifact.getId.getModuleRevisionId.getOrganisation == a.dep.organization
        if artifact.getId.getName == a.dep.name
        if (artifact.getExt == null) || (artifact.getExt == "jar")
      } yield a.local
   ).headOption
    
  
  
  // Note:  null indicates we didn't find anything.
  override def findIvyFileRef(dd: DependencyDescriptor, data: ResolveData): ResolvedResource = 
    null
    
  override def getDependency(dd: DependencyDescriptor, data: ResolveData): ResolvedModuleRevision =
    null
    
  override def getTypeName = "distributed-build-artifacts"
}