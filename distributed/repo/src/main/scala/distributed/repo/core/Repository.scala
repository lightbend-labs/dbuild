package distributed
package repo
package core

import java.io.File

/** Interface for a repository of raw key-value files. */
trait ReadableRepository {
  /** Retrieves the contents stored at a given key. */ 
  def get(key: String): File
}

/** Abstract trait representing the interface by which we can push/get files. */ 
trait Repository extends ReadableRepository {
  /** Puts the contents of a file into the given key. */
  def put(key: String, file: File): Unit
}

object Repository {
  /** Construct a repository that reads from a given URI and stores in a local cache. */
  def readingFrom(uri: String, cacheDir: File = defaultCacheDir): ReadableRepository = 
    new CachedRemoteReadableRepository(cacheDir, uri)
  /** Construct a repository that reads/writes to a given URI and stores in a local cache. */
  def remote(uri: String, cred: Credentials, cacheDir: File = defaultCacheDir): Repository =
    new CachedRemoteRepository(cacheDir, uri, cred)
  
  def defaultCacheDir =
    (sys.props get "user.home" 
       map (new File(_, ".dsbt/cache"))
       getOrElse new File(sbt.IO.temporaryDirectory, "dsbt-cache"))
}