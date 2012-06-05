package distributed
package project
package resolve

import model._

/** Represents an interface that is used
 * to resolve a given project locally so that
 * we can run its build system.
 */
trait ProjectResolver {
  /** returns whether or not a resolver can resolve a particular
   * type of project.
   */
  def canResolve(config: BuildConfig): Boolean
  /** Resolves a remote project into the given local directory.
   * Returns a new repeatable Scm configuration that can be used
   * to retrieve the *exact* same code retrieved by this resolve call.
   */
  def resolve(config: BuildConfig, dir: java.io.File): BuildConfig
}

/** Helper that uses all known project resolvers. */
object ProjectResolver extends ProjectResolver {
  val resolvers: Seq[ProjectResolver] = Seq(
    new support.git.GitProjectResolver
  )
  def canResolve(config: BuildConfig): Boolean = 
    resolvers exists (_ canResolve config)
  def resolve(config: BuildConfig, dir: java.io.File): BuildConfig = {
    resolvers find (_ canResolve config) match {
      case Some(r) => r.resolve(config, dir)
      case _       => sys.error("Could not find a resolver for: " + BuildConfig)
    }
  }
  
}