package com.typesafe.dbuild.repo.core

import java.io.File
import com.typesafe.dbuild.adapter.Adapter
import Adapter.IO
import Adapter.Path._
import dispatch.{url => dispUrl, Http}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import org.apache.commons.io.{ FileUtils, IOUtils }

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
  def push(uri: String, file: File, cred: Credentials, timeOut: Duration = 20 minutes): Unit = {
   // Send as binary always.
   val sender = 
    dispUrl(uri).PUT.as(cred.user,cred.pw).setBody(file).setBodyEncoding("application/octet-stream")
    val response = Await.result(Http(sender OK { response =>
      println(response.getResponseBody)
    }), timeOut)
  }
  def pull(uri: String, local: File, timeOut: Duration = 20 minutes): Unit = {
    // Ensure directory exists.
    local.getParentFile.mkdirs()
    
    // Pull to temporary file, then move.
    // uri must be sanitized first: can't contain slashes etc.
    val saneUri=java.net.URLEncoder.encode(uri)
    val suffix=saneUri.substring(Math.max(0,saneUri.length-45))
    IO.withTemporaryFile("dbuild-cache", suffix) { tmp =>
      val fous = new java.io.FileOutputStream(tmp)
      // IF there's an error, we must delete the file...
      try {
        Await.result(Http(dispUrl(uri).GET OK { response =>
          val stream = response.getResponseBodyAsStream
          try IOUtils.copy(response.getResponseBodyAsStream, fous)
          finally stream.close()
        }), timeOut)
      } finally {
        fous.close()
        // IF we made it here with no exceptions thrown, it's safe to move the temp file to the
        // appropriate location.  This should be a far more atomic operation, and "safer".
        IO.move(tmp, local)
      }
    }
  }
}
