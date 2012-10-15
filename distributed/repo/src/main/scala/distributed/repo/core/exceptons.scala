package distributed
package repo
package core


trait RepositoryException
/** This represents we had trouble resolving a particular key from the repository. */
case class ResolveException(key: String, msg: String) extends java.lang.RuntimeException(msg) with RepositoryException
/** We had issues reading the metadata, but it did exist. */
case class MalformedMetadata(key: String, msg: String) extends java.lang.RuntimeException(msg) with RepositoryException