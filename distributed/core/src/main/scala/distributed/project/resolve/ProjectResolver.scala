package distributed
package project
package resolve

import model._

/** Represents an interface that is used
 * to resolve a given project locally so that
 * we can run its build system.
 * 
 * This is an abstraction for a given SCM system where we can obtain projects.
 */
trait ProjectResolver {
  /** returns whether or not a resolver can resolve a particular
   * type of project.
   */
  def canResolve(uri: String): Boolean
  /**
   * Resolves a remote project into the given local directory.
   * Returns a new repeatable Scm configuration that can be used
   * to retrieve the *exact* same code retrieved by this resolve call.
   * Returns a modified ProjectBuildConfig, in which for example the
   * branch name has been replaced by a hash, or other transformations
   * have occurred in virtue of Resolver's knowledge.
   * 
   * IMPORTANT: For all the resolvers that may be used together with sbt,
   * at the end of resolve() no extraneous files are allowed
   * in the directory: only the project files must be there (the version
   * control metadata like .svn/.git/etc are allowed, but there should
   * be no extra source files, build directories, plugins, etc).
   * 
   * The directory "dir" must exist before resolve() is
   * called, and resolve() may rely on that. See
   * ProjectDirs.useProjectExtractionDirectory().
   */
  // TODO: it would be nice if resolve() could also return a Boolean
  // informing the caller of whether the checkout changed (this is a
  // bit tricky to detect with the current git resolver, however)
  def resolve(config: ProjectBuildConfig, dir: java.io.File, log: logging.Logger): ProjectBuildConfig
}

/** Helper that uses all known project resolvers. */
class AggregateProjectResolver(resolvers: Seq[ProjectResolver]) extends ProjectResolver {
  def findResolver(config: ProjectBuildConfig) = {
    resolvers find (r => try {
      r canResolve config.uri
    } catch {
      case e: java.net.URISyntaxException => false
    }) match {
      case Some(r) => r
      case _ => sys.error("Could not find a resolver for: " + config.name)
    }
  }
  def canResolve(config: String): Boolean = 
    resolvers exists (_ canResolve config)
  def resolve(config: ProjectBuildConfig, dir: java.io.File, log: logging.Logger): ProjectBuildConfig =
    findResolver(config).resolve(config, dir, log)
}
