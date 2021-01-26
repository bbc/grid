package com.gu.mediaservice.lib.auth.provider

import com.gu.mediaservice.lib.auth.Authentication.Principal
import com.gu.mediaservice.lib.auth.Permissions.{ParameterPredicate, PrincipalPredicate}
import com.gu.mediaservice.lib.auth.{PermissionWithParameter, SimplePermission}
import com.gu.mediaservice.lib.config.CommonConfig
import play.api.libs.ws.WSClient

case class AuthorisationProviderResources(commonConfig: CommonConfig, wsClient: WSClient)

trait AuthorisationProvider {
  def isReady(): Boolean
  def principalPredicate[T](permission: SimplePermission): PrincipalPredicate
  def principalPredicate[T](permission: PermissionWithParameter[T], parameter: T): PrincipalPredicate
  def filterPredicate[T](permission: PermissionWithParameter[T], principal: Principal): ParameterPredicate[T]
}
