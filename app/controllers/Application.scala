package controllers

import akka.actor.Props
import play.api._
import play.api.mvc._
import play.api.Play.current
import play.libs.Akka

object Application extends Controller {

  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  def start(nick: String) = Action {
    Ok(views.html.game(nick))
  }

  val registry = Akka.system().actorOf(Props[RegistryActor], "registry-actor")

  def socket(nick: String) = WebSocket.acceptWithActor[String, String] { request => out =>
    PlayerActor.props(out, registry, nick)
  }

  def spawn = Action {
    Akka.system.actorOf(Props(new NPCActor(registry)))
    Ok("Ok")
  }

}