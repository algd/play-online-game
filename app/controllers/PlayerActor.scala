package controllers

import akka.actor._
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.Future

object RegistryActor {
  case class Register(id: Int)
  case class Disconnect(id: Int)
}

class RegistryActor extends Actor {
  import RegistryActor._
  import context.dispatcher
  var map = scala.collection.mutable.Map[Int, PlayerStatus]()
  def receive = {
    case Register(id) =>
      val currMap = map.toMap
      Future(currMap.values.foreach {
          status => status.actor ! Command("", Some(Connected(id, 0, 0)), Some(id))
        })
      map += (id -> PlayerStatus(sender()))
      sender() ! Command("", Some(Initialize(map.toMap.map { case (id, status) =>
        Connected(id, status.x, status.y)
      }.toSeq)), Some(id))
    case Disconnect(id) =>
      map -= id
      Future(map.toMap.values.foreach {
        status => status.actor ! Command("", Some(Disconnected(id)), Some(id))
      })
    case cmd@Command(_, params, Some(id)) =>
      Future(map.toMap.values.foreach {
        status => status.actor ! cmd
      })
      params.foreach{
        case Move(x, y) => map.get(id).foreach {
          status => map += (id -> status.copy(x = x, y = y))
        }
        case _ =>
      }
  }
}

object PlayerActor {
  def props(out: ActorRef, registry: ActorRef, nick: String) = Props(new PlayerActor(out, registry, nick))
}

case class PlayerStatus(actor: ActorRef, x: Int = 0, y: Int = 0)

case class Command(action: String, params: Option[Parameters], id: Option[Int] = None)
trait Parameters
case class Move(x: Int, y: Int) extends Parameters
case class Hi(id: Int) extends Parameters
case class Say(msg: String) extends Parameters
case class Connected(id: Int, x: Int, y: Int) extends Parameters
case class Disconnected(id: Int) extends Parameters
case class Initialize(connections: Seq[Connected]) extends Parameters

class NPCActor(registry: ActorRef) extends Actor {
  import RegistryActor._

  val id = scala.util.Random.nextInt(1000)
  var tick: Cancellable = _

  import context._

  override def preStart() = {
    super.preStart()
    registry ! Register(id)
    import scala.concurrent.duration._
    tick = context.system.scheduler.schedule(3.seconds, 5.seconds, self, Tick)
  }

  override def postStop() = {
    super.postStop()
    tick.cancel()
    registry ! Disconnect(id)
  }

  case object Tick

  def receive = {
    case Command(_, Some(Say(msg)), Some(msgId)) =>
      if (id != msgId && msg.contains("hola"))
        Future {
          Thread.sleep(1000)
          registry ! Command("", Some(Say("Bot: hola "+msg.split(":").head+"!")), Some(id))
        }

    case Tick =>
        registry ! Command("", Some(Move(scala.util.Random.nextInt(300), scala.util.Random.nextInt(300))), Some(id))

  }
}

class PlayerActor(out: ActorRef, registry: ActorRef, nick: String) extends Actor {
  import RegistryActor._

  val id = scala.util.Random.nextInt(1000)
  implicit val moveFormat =  Json.format[Move]
  implicit val connectedFormat =  Json.format[Connected]
  implicit val initializeFormat =  Json.format[Initialize]
  implicit val disconectedFormat =  Json.format[Disconnected]
  implicit val sayFormat =  Json.format[Say]
  implicit val commandFormat = new Format[Command]{
    def reads(json : JsValue) = {
      val (action, params) = ((json \ "action").as[String], json \ "params")
      JsSuccess(Command(action, action match {
        case "move" => params.asOpt[Move]
        case "say" => params.asOpt[Say]
      }))
    }
    def writes(c: Command) = c match {
      case Command(_, Some(params: Move), Some(id)) =>
        JsObject(Seq("action" -> JsString("move"),
          "id" -> JsNumber(id),
          "params" -> Json.toJson(params)))
      case Command(_, Some(params: Connected), Some(id)) =>
        JsObject(Seq("action" -> JsString("connected"),
          "id" -> JsNumber(id),
          "params" -> Json.toJson(params)))
      case Command(_, Some(params: Initialize), Some(id)) =>
        JsObject(Seq("action" -> JsString("initialize"),
          "id" -> JsNumber(id),
          "params" -> Json.toJson(params)))
      case Command(_, Some(params: Disconnected), Some(id)) =>
        JsObject(Seq("action" -> JsString("disconnected"),
          "id" -> JsNumber(id),
          "params" -> Json.toJson(params)))
      case Command(_, Some(params: Say), Some(id)) =>
        JsObject(Seq("action" -> JsString("say"),
          "id" -> JsNumber(id),
           "params" -> Json.toJson(params)))
      case Command(other, None, Some(id)) =>
        JsObject(Seq("action" -> JsString(other),
          "id"-> JsNumber(id)))
      case _ => null
    }
  }

  override def preStart() = {
    super.preStart()
    registry ! Register(id)
    self ! Command("hi", None, Some(id))
  }

  override def postStop() = {
    super.postStop()
    registry ! Disconnect(id)
  }

  def receive = {
    case msg: String =>
      val value = Json.parse(msg).as[Command]
      registry ! value.copy(id = Some(id))
    case cmd: Command  =>
      out ! Json.toJson(cmd).toString()

  }
}