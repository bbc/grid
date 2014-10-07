package controllers

import play.api.mvc.{Action, Controller}
import play.api.libs.concurrent.Execution.Implicits._
import com.gu.mediaservice.lib.auth.PanDomainAuthActions

object Panda extends Controller with PanDomainAuthActions {

  def doLogin = Action.async { implicit request =>
    sendForAuth
  }

  def oauthCallback = Action.async { implicit request =>
    processGoogleCallback()
  }

  def logout = Action { implicit request =>
    processLogout
  }

}
