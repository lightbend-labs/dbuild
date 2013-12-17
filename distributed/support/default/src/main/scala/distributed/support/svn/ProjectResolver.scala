package distributed
package support
package svn

import _root_.sbt.Path._
import project.model._
import project.resolve.ProjectResolver

/** This class knows how to resolve Git projects and
 * update the build configuration for repeatable checkouts.
 */
class SvnProjectResolver extends ProjectResolver {
  def canResolve(configUri: String): Boolean = {
    val uri = new _root_.java.net.URI(configUri)
    ((uri.getScheme == "svn") || 
     (uri.getScheme == "http") ||
     (uri.getScheme == "https")
    ) && Svn.isSvnRepo(uri)
  }
    
  def resolve(config: ProjectBuildConfig, dir: _root_.java.io.File, log: logging.Logger): ProjectBuildConfig = {
    val uri = new _root_.java.net.URI(config.uri)

    // First clone into the directory or fetch
    // TODO - better git checkout detection...
    if(!dir.getParentFile.exists) dir.getParentFile.mkdirs()
    if(!(dir / ".svn" ).exists) Svn.checkout(uri, dir, log)
    else Svn.revert(dir, log)

    // TODO - Fetch non-standard references?
    // Then checkout desired branch/commit/etc.
    Option(uri.getFragment()) match {
      case Some(revision) => Svn.update(revision, dir, log)
      case _ => Svn.update("", dir, log)
    }
    val rev = Svn.revision(dir, log)
    val newUri = UriUtil.dropFragment(uri).toASCIIString + "#" + rev
    config.copy(uri = newUri)
  }
}