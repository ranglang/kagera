package io.process.statebox.process

import akka.actor.ActorRef
import io.process.statebox.Transition

object PetriNetDebugging {
  sealed trait Command

  case class SetBreakPoint(t:Transition, receiver:ActorRef) extends Command
  case object Step extends Command
  case object Resume extends Command
  case class RemoveBreakPoint(t: Transition) extends Command
}

trait PetriNetDebugging {

  self:PetriNetActor =>

  val breakPoints:Map[Transition, ActorRef]

  override def receiveCommand: Unit = {


  }
}