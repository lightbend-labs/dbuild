package distributed
package repo
package core

// TODO - rename this source file
trait RepositoryException
/** This represents we had trouble resolving a particular key from the repository. */
case class ResolveException(key: String, msg: String) extends java.lang.RuntimeException(msg) with RepositoryException
/** We had issues reading the metadata, but it did exist. */
case class MalformedMetadata(key: String, msg: String) extends java.lang.RuntimeException(msg) with RepositoryException
/** We had an issue attempting to store the artifact in the cache. */
case class StoreException(key: String, msg: String) extends java.lang.RuntimeException(msg) with RepositoryException
/** We were copying files from the local repo to a new Scala "lib" directory, but a needed file couldn't be copied/found */
case class LocalArtifactMissingException(msg: String) extends java.lang.RuntimeException(msg) with RepositoryException
