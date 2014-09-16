package com.typesafe.dbuild.project.build
import akka.actor.ActorRef

object ActorPatterns {
  
  @inline
  final def forwardingErrorsToFutures[A](sender: ActorRef)(f: => A): A =
    try f catch {
      case e: Exception =>
        sender ! akka.actor.Status.Failure(e)
        throw e
    }
}