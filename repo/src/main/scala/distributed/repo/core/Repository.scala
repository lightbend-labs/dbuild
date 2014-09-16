package distributed
package repo
package core

import java.io.File
import sbt.Path._

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

  def default: Repository = {
    // Look for repository/credentials file
    def cacheDir = sysPropsCacheDir getOrElse defaultUserHomeCacheDir
    def repoCredFile = GlobalDirs.repoCredFile

    def readCredFile(f: File): Option[(String, Credentials)] =
      if (f.exists) {
        val props = new java.util.Properties
        sbt.IO.load(props, f)
        def getProp(name: String): Option[String] =
          Option(props getProperty name)
        for {
          url <- getProp("remote.url")
          user <- getProp("remote.user")
          pw <- getProp("remote.password")
        } yield url -> Credentials(user, pw)
      } else None

    def remoteRepo =
      for {
        (url, credentials) <- readCredFile(repoCredFile)
      } yield remote(url, credentials, cacheDir)
    def localRepo = local(cacheDir)
    remoteRepo getOrElse localRepo
  }
  
  /** Construct a repository that reads from a given URI and stores in a local cache. */
  def readingFrom(uri: String, cacheDir: File = defaultCacheDir): ReadableRepository = 
    new CachedRemoteReadableRepository(cacheDir, uri)
  /** Construct a repository that reads/writes to a given URI and stores in a local cache. */
  def remote(uri: String, cred: Credentials, cacheDir: File = defaultCacheDir): Repository =
    new CachedRemoteRepository(cacheDir, uri, cred)
  
  def localCache(cacheDir: File = defaultCacheDir): ReadableRepository =
    new OfflineLocalCacheRepository(cacheDir)
  
  def local(cacheDir: File = defaultCacheDir): Repository = 
    new LocalRepository(cacheDir)
  
  def defaultCacheDir = sysPropsCacheDir getOrElse defaultUserHomeCacheDir
    
  def sysPropsCacheDir =
    sys.props get "dbuild.cache.dir" map (new File(_))
  
  def defaultUserHomeCacheDir = GlobalDirs.userCache
       
}
