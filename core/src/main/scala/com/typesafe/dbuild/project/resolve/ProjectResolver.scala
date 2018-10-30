package com.typesafe.dbuild.project.resolve

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.logging

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
   * The directory "dir" must exist before resolve() is
   * called, and resolve() may rely on that. See
   * ProjectDirs.useProjectExtractionDirectory().
   *
   * The main role of resolve() is to resolve the URI into a stable
   * and repeatable URI; it may or may not also alter the files
   * contained in the directory, which is not yet guaranteed to
   * contain a buildable source tree. Before building or extracting,
   * the caller must call prepare(), which will reconstruct the
   * appropriate source tree.
   */
  def resolve(config: ProjectBuildConfig, dir: java.io.File, log: logging.Logger): ProjectBuildConfig
  /**
   * After resolve() has been called, prepare() will set up the source
   * tree as needed for building or extraction. It is mandatory to call
   * prepare(), since resolve() is not guaranteed to leave the directory
   * in a buildable state.
   * An implementation of ProjectResolver may decide to have resolve()
   * prepare the full tree, and not actually do anything in prepare();
   * in any case, the caller must not alter the directory tree between
   * resolve() and prepare().
   * prepare() MUST be supplied the ProjectBuildConfig returned by resolve().
   *
   * IMPORTANT: For all the resolvers that may be used together with sbt,
   * at the end of prepare() no extraneous files are allowed
   * in the directory: only the project files must be there (the version
   * control metadata like .svn/.git/etc are allowed, but there should
   * be no extra source files, build directories, plugins, etc).
   *
   */
  // by default, prepare() doesn't do anything and all is done by prepare()
  def prepare(config: ProjectBuildConfig, dir: java.io.File, log: logging.Logger) = {}
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
  def resolve(config: ProjectBuildConfig, dir: java.io.File, log: logging.Logger): ProjectBuildConfig = {
    log.debug("Resolving project: " + config.name)
    findResolver(config).resolve(config, dir, log)
  }
  override def prepare(config: ProjectBuildConfig, dir: java.io.File, log: logging.Logger) = {
    log.debug("Preparing project source tree: " + config.name)
    findResolver(config).prepare(config, dir, log)
  }
}
