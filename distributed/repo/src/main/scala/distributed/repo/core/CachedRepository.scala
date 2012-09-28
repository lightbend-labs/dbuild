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
      Remote pull (makeUrl(uri, key), cacheFile)
    cacheFile
  }
}

/** A cached remote repository where we can update files. */
final class CachedRemoteRepository(cacheDir: File, uri: String, credentials: Credentials) 
    extends CachedRemoteReadableRepository(cacheDir, uri) 
    with Repository {
  def put(key: String, file: File): Unit = {
    val cacheFile = new File(cacheDir, key)
    // TODO - immutability restrictions?
    Remote push (makeUrl(uri, key), file, credentials)
    IO.copyFile(file, cacheFile)
  }
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
    import dispatch._
    val fous = new java.io.FileOutputStream(local)
    try Http(url(uri) >>> fous) finally fous.close()
  }
}