package distributed
package repo
package core

import java.io.File
import sbt.IO

/** A cached remote repository. */
class CachedRemoteReadableRepository(cacheDir: File, uri: String) extends ReadableRepository {
  if(!cacheDir.exists) cacheDir.mkdirs()
  
  protected def makeUrl(args: String*) = args mkString "/"
  
  def get(key: String): File  = {
    val cacheFile = new File(cacheDir, key)
    // TODO - Are we guaranteed uniqueness?  Should we embed knowledge of
    // `raw` vs `meta` keys here?
    // For now, let's assume immutable repos.
    if(!cacheFile.exists)
      try Remote pull (makeUrl(uri, key), cacheFile)
      catch {
        case e: Exception =>
          throw new ResolveException(key, e.getMessage)
      }
    cacheFile
  }
}

/** A cached remote repository where we can update files. */
final class CachedRemoteRepository(cacheDir: File, uri: String, credentials: Credentials) 
    extends CachedRemoteReadableRepository(cacheDir, uri) 
    with Repository {
  def put(key: String, file: File): Unit = {
    val cacheFile = new File(cacheDir, key)
    // IF the cache file exists, we assume this file has already been pushed...
    // If we get corrupted files, we should check these and evict them.  For now, let's just
    // avoid 0-length files, which represents some kind of error downloaded that we've cleaned up,
    // but may reappair on file system exceptions.
    if(!cacheFile.exists || cacheFile.length == 0) {
      // TODO - immutability restrictions?
      try {
        Remote push (makeUrl(uri, key), file, credentials)
        IO.copyFile(file, cacheFile)
      } catch {
        case t: Exception =>
          throw new StoreException(key, t.getMessage())
      }      
    }
  }
  
  
  
  override def toString = "CachedRemoteRepository(uri="+uri+", cache="+cacheDir+")"
}

/** Helpers for free-form HTTP repositories */
object Remote {
  def push(uri: String, file: File, cred: Credentials): Unit = {
   import dispatch._
   // TODO - Discover mime type from file extension if necessary, or just send
   // as binary always.
   val sender = 
     url(uri).PUT.as(cred.user,cred.pw) <<< (file, "application/octet-stream")
    // TODO - output to logger.
    Http(sender >>> System.out)
  }
  def pull(uri: String, local: File): Unit = {
    // Ensure directory exists.
    local.getParentFile.mkdirs()
    
    // Pull to temporary file, then move.
    IO.withTemporaryFile("dsbt-cache", uri) { tmp =>
      import dispatch._
      val fous = new java.io.FileOutputStream(tmp)
      // IF there's an error, we must delete the file...
      try Http(url(uri) >>> fous)
      finally fous.close()
      // IF we made it here with no exceptions thrown, it's safe to move the temp file to the
      // appropriate location.  This should be a far more atomic operation, and "safer".
      IO.move(tmp, local)
    }
    
  }
}