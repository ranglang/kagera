package io.kagera.akka.actor

import java.util.concurrent.{ BlockingQueue, LinkedBlockingQueue, TimeUnit }

import akka.NotUsed
import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.Timeout
import cats.data.Xor
import io.kagera.akka.actor.PetriNetInstanceProtocol._
import io.kagera.api.colored.ExceptionStrategy.RetryWithDelay
import io.kagera.api.colored.{ Transition, _ }

import scala.collection.immutable.Seq
import scala.concurrent.{ Await, Future }

/**
 * Contains some methods to interact with a petri net instance actor.
 */
object PetriNetInstanceApi {

  case class Error(msg: String)

  /**
   * An actor that pushes all received messages on a blocking queue.
   */
  class QueuePushingActor[E](queue: BlockingQueue[E], takeWhile: Any ⇒ Boolean) extends Actor {
    override def receive: Receive = {
      case msg @ _ ⇒
        queue.add(msg.asInstanceOf[E])
        if (!takeWhile(msg))
          context.stop(self)
    }
  }

  implicit class IteratorExtension[A](i: Iterator[A]) {
    def takeWhileInclusive(p: A ⇒ Boolean): Iterator[A] = {
      val (a, b) = i.span(p)
      a ++ b.take(1)
    }
  }

  def hasAutomaticTransitions[S](topology: ExecutablePetriNet[S]): InstanceState[S] ⇒ Boolean = state ⇒ {
    state.marking.keySet.map(p ⇒ topology.outgoingTransitions(p)).foldLeft(Set.empty[Transition[_, _, _]]) {
      case (result, transitions) ⇒ result ++ transitions
    }.exists(isEnabledInState(topology, state))
  }

  def isEnabledInState[S](topology: ExecutablePetriNet[S], state: InstanceState[S])(t: Transition[_, _, _]): Boolean =
    t.isAutomated && !state.hasFailed(t.id) && topology.isEnabledInMarking(state.marking.multiplicities)(t)

  def takeWhileNotFailed[S](topology: ExecutablePetriNet[S], waitForRetries: Boolean): Any ⇒ Boolean = e ⇒ e match {
    case e: TransitionFired[S]                               ⇒ hasAutomaticTransitions(topology)(e.result)
    case TransitionFailed(_, _, _, _, RetryWithDelay(delay)) ⇒ waitForRetries
    case msg @ _                                             ⇒ false
  }

  implicit class ActorRefAdditions(actor: ActorRef)(implicit actorSystem: ActorSystem, materializer: Materializer) {

    import actorSystem.dispatcher

    def responseIterator[E](msg: Any, takeWhile: Any ⇒ Boolean)(implicit timeout: Timeout): Iterator[E] = {
      val queue = new LinkedBlockingQueue[E]()
      val askingActor = actorSystem.actorOf(Props(new QueuePushingActor[E](queue, takeWhile)))
      actor.tell(msg, askingActor)
      Iterator.continually(queue.poll(timeout.duration.toMillis, TimeUnit.MILLISECONDS)).takeWhileInclusive(takeWhile)
    }

    def responseSource[E](msg: Any, takeWhile: Any ⇒ Boolean)(implicit timeout: Timeout): Source[E, NotUsed] = {
      Source.fromIterator(() ⇒ responseIterator(msg, takeWhile))
    }

    /**
     * Fires a transition and confirms (waits) for the result of that transition firing.
     */
    def fireAndConfirmFirst[S](topology: ExecutablePetriNet[S], msg: Any)(implicit timeout: Timeout): Future[Xor[Error, InstanceState[S]]] = {
      actor.ask(msg).map {
        case e: TransitionFired[_] ⇒ Xor.Right(e.result.asInstanceOf[InstanceState[S]])
        case msg @ _               ⇒ Xor.Left(Error(s"Received unexepected message: $msg"))
      }
    }

    def fireAndConfirmFirstSync[S](topology: ExecutablePetriNet[S], msg: Any)(implicit timeout: Timeout): Xor[Error, InstanceState[S]] = {
      Await.result(fireAndConfirmFirst(topology, msg), timeout.duration)
    }

    /**
     * Fires a transition and confirms (waits) for all responses of subsequent automated transitions.
     */
    def fireAndConfirmAll[S](topology: ExecutablePetriNet[S], msg: Any, waitForRetries: Boolean = false)(implicit timeout: Timeout): Future[Xor[Error, InstanceState[S]]] = {

      val futureMessages = fireAndCollectAll(topology, msg, waitForRetries).runWith(Sink.seq)

      futureMessages.map {
        _.last match {
          case e: TransitionFired[_] ⇒ Xor.Right(e.result.asInstanceOf[InstanceState[S]])
          case msg @ _               ⇒ Xor.Left(Error(s"Received unexpected message: $msg"))
        }
      }
    }

    /**
     * Collects
     */
    def fireAndCollectAllSync[S](topology: ExecutablePetriNet[S], msg: Any, waitForRetries: Boolean = false)(implicit timeout: Timeout): Seq[TransitionResponse] = {
      val futureResult = fireAndCollectAll(topology, msg, waitForRetries).runWith(Sink.seq)
      Await.result(futureResult, timeout.duration)
    }

    /**
     * Collects all the messages from the petri net actor in reponse to a message
     */
    def fireAndCollectAll[S](topology: ExecutablePetriNet[S], msg: Any, waitForRetries: Boolean = false)(implicit timeout: Timeout): Source[TransitionResponse, NotUsed] = {
      responseSource[Any](msg, takeWhileNotFailed(topology, waitForRetries)).map {
        case e: TransitionResponse ⇒ e
        case msg @ _               ⇒ throw new RuntimeException(s"Unexepected response message: $msg")
      }
    }
  }
}