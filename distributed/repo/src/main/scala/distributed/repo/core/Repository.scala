package distributed
package repo
package core

import java.io.File

/** Interface for a repository of raw key-value files. */
trait ReadableRepository {
  /** Retrieves the contents stored at a given key. */ 
  def get(key: String): File
  /** TODO - Cheating interface.
   * This assumes that keys with '/' in them are *directory paths* and looks for sub-keys in the repository.
   */
  def subKeys(key: String): Seq[String]
}

/** Abstract trait representing the interface by which we can push/get files. */ 
trait Repository extends ReadableRepository {
  /** Puts the contents of a file into the given key. */
  def put(key: String, file: File): Unit
}

object Repository {
  
  def default: Repository = {
    // Look for repository/credentials file
    def cacheDir = sysPropsCacheDir orElse defaultUserHomeCacheDir
    def repoCredFile = cacheDir map (new File(_, "../remote.cache.properties"))
    def readCredFile(f: File): Option[(String, Credentials)] = {
      val props = new java.util.Properties
      sbt.IO.load(props, f)
      def getProp(name: String): Option[String] =
        Option(props getProperty name)
      for {
        url <- getProp("remote.url")
        user <- getProp("remote.user")
        pw <- getProp("remote.password")
      } yield url -> Credentials(user, pw)
    }
    def remoteRepo =
      for {
        c <- cacheDir
        f <- repoCredFile
        if f.exists
        (url, credentials) <- readCredFile(f)
      } yield remote(url, credentials, c)
    def localRepo = cacheDir map local 
    remoteRepo orElse localRepo getOrElse sys.error("Unable to initialize default repository.")  
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
  
  def defaultCacheDir =
    sysPropsCacheDir orElse defaultUserHomeCacheDir getOrElse sys.error("Could not find default caching directory for DSBT repository.")
    
  def sysPropsCacheDir =
    sys.props get "dsbt.cache.dir" map (new File(_))
  
  def defaultUserHomeDir = sys.props get "user.home" 
  
  def defaultUserHomeCacheDir =
     defaultUserHomeDir map (new File(_, ".dsbt/cache"))
       
}