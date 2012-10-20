package distributed
package repo
package server

import repo.core.Repository
import unfiltered.request._
import unfiltered.response._
import unfiltered.netty.cycle._


object RepoMain {
  
  // TODO - this file-specific knowledge is evil
  val cacheDir = Repository.defaultCacheDir
  val cache = Repository.localCache()
  
  def main(args: Array[String]): Unit = {
    // TODO - Accept port on the command line.
    unfiltered.netty.Http(8080)
      .handler(RepoRules.buildRepoRules(cache))
      .handler(Planify {
        case GET(Path(path)) => Html(<html><head><title>Unknown request</title></head><body>Unknown request path: {path}</body></html>)
      })
      .run { s =>
         println("starting build repository at localhost on port %s" format (s.port))
      }
  }
}