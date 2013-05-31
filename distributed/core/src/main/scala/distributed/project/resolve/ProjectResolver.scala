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
  def canResolve(config: ProjectBuildConfig): Boolean
  /** Resolves a remote project into the given local directory.
   * Returns a new repeatable Scm configuration that can be used
   * to retrieve the *exact* same code retrieved by this resolve call.
   */
  def resolve(config: ProjectBuildConfig, dir: java.io.File, log: logging.Logger): ProjectBuildConfig
}

/** Helper that uses all known project resolvers. */
class AggregateProjectResolver(resolvers: Seq[ProjectResolver]) extends ProjectResolver {
  def canResolve(config: ProjectBuildConfig): Boolean = 
    resolvers exists (_ canResolve config)
  def resolve(config: ProjectBuildConfig, dir: java.io.File, log: logging.Logger): ProjectBuildConfig = {
    resolvers find (_ canResolve config) match {
      case Some(r) => r.resolve(config, dir, log)
      case _       => sys.error("Could not find a resolver for: " + config.name)
    }
  }
  
}
