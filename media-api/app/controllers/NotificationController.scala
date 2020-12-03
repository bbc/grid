package controllers

import play.api.mvc._
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.ActorMaterializer
import com.gu.mediaservice.lib.auth.Authentication
import com.gu.mediaservice.lib.auth.Authentication.PandaUser
import lib.VirusStatusSqsMessageConsumer
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.json.JsValue

import scala.concurrent.duration._
import scala.concurrent.Future

class NotificationController(auth: Authentication, consumer: VirusStatusSqsMessageConsumer, override val controllerComponents: ControllerComponents) extends  BaseController {
  implicit val system = ActorSystem("NotificationSystem")
  implicit val materializer = ActorMaterializer()

  def serverSentEvent = auth.async { request =>
  val user =  request.user match {
      case user: PandaUser => Some(user.user.email.toLowerCase())
      case _ => None
    }
    Future.successful(Ok.chunked(notificationSource(user) via EventSource.flow).as(ContentTypes.EVENT_STREAM))
  }

  def notificationSource(user: Option[String]): Source[JsValue, _] = {
    val tickSource = Source.tick(0.millis, 100.millis, "TICK")
    val s = tickSource.map(_ => consumer.getNotificationMsg(user) )
    s
  }
}
