import akka.actor.ActorRef

package object actorpaterns {
  
  @inline
  final def forwardingErrorsToFutures[A](sender: ActorRef)(f: => A): A =
    try f catch {
      case e: Exception =>
        sender ! akka.actor.Status.Failure(e)
        throw e
    }
}