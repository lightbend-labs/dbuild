package distributed
package files

import java.io.File
import akka.actor.Actor

/** This represents an Actor which stores the URL references for local files.
 * 
 */
class LocalFileRepositoryActor extends Actor {
  var cache: Map[String, File] = Map.empty 
  def receive = {
    case AddFile(name, file) => cache = cache.updated(name, file)
    case GetFile(name) => cache get name match {
      case Some(file) => sender ! FileFound(name, file)
      case _ => sender ! FileNotFound(name)
    }
  }
}
