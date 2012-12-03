package distributed
package repo
package core

import java.io.File
import sbt.IO

/** A cached remote repository. */
class OfflineLocalCacheRepository(cacheDir: File) extends ReadableRepository {
  def get(key: String): File  = {
    val cacheFile = new File(cacheDir, key)
    // TODO - Are we guaranteed uniqueness?  Should we embed knowledge of
    // `raw` vs `meta` keys here?
    // For now, let's assume immutable repos.
    if(!cacheFile.exists)
      throw new ResolveException(key, "Key ["+key+"] does not exist!")
    cacheFile
  }
  
  def subKeys(key: String): Seq[String] = {
    val cacheFile = new File(cacheDir, key)
    if(cacheFile.isDirectory) {
      (IO listFiles cacheFile) map (_.getName) map (key + "/" + _)
    } else Seq.empty
  }
}

class LocalRepository(repo: File) 
    extends OfflineLocalCacheRepository(repo)
    with Repository {
  
  
  def put(key: String, file: File): Unit = {
    val cacheFile = new File(repo, key)
    IO.copyFile(file, cacheFile, false)
  }
}