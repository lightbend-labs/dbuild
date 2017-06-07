package com.typesafe.dbuild.repo.core

import java.io.File
import com.typesafe.dbuild.adapter.Adapter
import com.typesafe.dbuild.http._
import Adapter.IO
import Adapter.Path._
import com.typesafe.dbuild.adapter.Defaults
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
    val ht = new HttpTransfer(Defaults.version)
    try {
      ht.upload(uri, file, cred)(println)
    } finally {
      ht.close()
    }
  }
  def pull(uri: String, local: File, timeOut: Duration = 20 minutes): Unit = {
    // Ensure directory exists.
    local.getParentFile.mkdirs()
    val ht = new HttpTransfer(Defaults.version)
    try {
      ht.download(uri, local)
    } finally {
      ht.close()
    }
  }
}
