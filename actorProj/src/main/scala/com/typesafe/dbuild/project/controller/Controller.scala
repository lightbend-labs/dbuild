package com.typesafe.dbuild.project.controller

import akka.actor.{ Actor, Props, ActorRef, ActorContext }
import scala.collection.mutable.Queue
// import akka.routing.SmallestMailboxRouter
import akka.routing.BalancingPool
import akka.actor.actorRef2Scala
import com.typesafe.dbuild.project.build.ActorPatterns

/**
 * This layer is interposed between SimpleBuildActor and the two BuildRunnerActor and ExtractorActor.
 * It is used to make sure there are no more than "capacity" requests concurrently sent out at any
 * given time. The point is optimizing CPU utilization; with a simple RoundRobinRouter, messages are divided
 * into multiple queues immediately (one queue per child actor); if one of the children exhausts
 * all of its jobs immediately, one CPU core would remain pointlessly idle. Instead, Controller implements a
 * straightforward semaphore, by which BuildRunnerActors/ExtractorActors will only receive a message when
 * there is an actual core available.
 *
 * Send your requests to the Controller as if you were sending them to the wrapped actor. The wrapped actor
 * will receive from the Controller a Controlled(msg,from) request, where "from" is the original sender; the
 * response, wrapped into a Done(msg,from) where from is the same found in the Controlled(), is to be sent
 * to the sender (aka the Controller), which will unwrap it and return it to the original requester.
 */
class Controller(capacity: Int, wrapped: Props, name: String) extends Actor {
  private var available: Int = capacity
//  private val target = context.actorOf(wrapped.withRouter(SmallestMailboxRouter(nrOfInstances = capacity)), name)
//  private val target = context.actorOf(wrapped.withDispatcher("balancingDispatcher").withRouter(new RoundRobinRouter(capacity)), name)
  private val target = context.actorOf(BalancingPool(capacity).props(wrapped), name)

  def receive = {
    ActorPatterns.forwardingErrorsToFutures(sender) {
      case m: Done => m.dest ! m.msg
      case m: Any => target ! Controlled(m, sender)
    }
  }
}

case class Controlled(msg: Any, from: ActorRef)
case class Done(msg: Any, dest: ActorRef)
object Controller {
  def apply(context: ActorContext, wrapped: Props, name: String, capacity: Int): ActorRef =
    context.actorOf(Props(new Controller(capacity, wrapped, name)), "Controller-" + name)

  // Similar to the one in ActorPatterns, but wraps the reply into a Done()
  @inline
  final def forwardingErrorsToFuturesControlled[A](sender:ActorRef, from: ActorRef)(f: => A): A =
    try f catch {
      case e: Exception =>
        sender ! Done(akka.actor.Status.Failure(e), from)
        throw e
    }
}
