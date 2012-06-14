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


object DistributedDependencyResolver {
  def apply() = new RawRepository(new DistributedDependencyResolver)
}

/** A dependency resolver that should pull dependencies from recently
 * built distributed projects.
 */
class DistributedDependencyResolver extends AbstractResolver with DependencyResolver {
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
    report setDownloadStatus DownloadStatus.FAILED
    report setDownloadTimeMillis 0L
    report setDownloadDetails "Not yet implemented."
    report
  }
    
  
  
  // Note:  null indicates we didn't find anything.
  override def findIvyFileRef(dd: DependencyDescriptor, data: ResolveData): ResolvedResource = 
    null
    
  override def getDependency(dd: DependencyDescriptor, data: ResolveData): ResolvedModuleRevision =
    null
    
  override def getTypeName = "distributed-build-artifacts"
}