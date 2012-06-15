package distributed
package files

import java.io.File
import akka.actor.{Actor,ActorRef,Props}


/** 
 * This represents an Actor which stores the URL references for local files.
 */
class LocalFileRepositoryManagerActor extends Actor {
  var cache: Map[String, ActorRef] = Map.empty 
  import LocalFileRepositoryManagerActor._
  def receive = {
    case AddRepository(name: String) =>
      // TODO - Needed?
      sender ! addRepo(name)
    case AddFile(RepoUri(name, uri), file) =>
      cache get name match {
        case Some(repo) =>  repo ! AddFile(uri, file)
        case None => addRepo(name) ! AddFile(uri, file)
      }
    case GetFile(RepoUri(name, uri)) =>
      cache get name match {
        case Some(repo) =>  
          val tmp = context.actorOf(Props(new RepoDelegator(name, sender)))
          repo.!(GetFile(uri))(tmp)
        case None =>  sender ! FileNotFound(uri)
      }
  }
  
  def addRepo(name: String): ActorRef = {
    val repo = context.actorOf(Props(new LocalFileRepositoryActor), name="Repo: " + name)
    cache = cache.updated(name, repo)
    repo
  }
}
object LocalFileRepositoryManagerActor {
    
  object RepoUri {
    def unapply(in: String): Option[(String,String)] = 
      in split "/" filterNot (_.isEmpty) match {
        case Array(repo, uri @ _*) => Some(repo -> (uri mkString "/")) 
        case _           => None
      }
    def apply(name: String, uri: String): String = name + "/" + name
  }
  class RepoDelegator(name: String, listener: ActorRef) extends Actor {
    def receive = {
      case FileFound(uri, file) =>
        listener ! FileFound(name + "/" + uri, file)
        context stop self
      case FileNotFound(uri) =>
        listener ! FileNotFound(name + "/" + uri)
        context stop self
    }
  }
}
