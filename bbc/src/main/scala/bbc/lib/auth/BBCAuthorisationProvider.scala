package bbc.lib.auth

import com.gu.mediaservice.lib.auth.Authentication.Principal
import com.gu.mediaservice.lib.auth.Permissions._
import com.gu.mediaservice.lib.auth.provider.{AuthorisationProvider, AuthorisationProviderResources}
import com.gu.mediaservice.lib.auth.{PermissionContext, PermissionWithParameter}
import com.gu.mediaservice.lib.config.CommonConfig
import com.typesafe.scalalogging.StrictLogging
import play.api.Configuration
import play.api.libs.typedmap.TypedKey

class BBCAuthorisationProvider(configuration: Configuration, resources: AuthorisationProviderResources)
  extends AuthorisationProvider with StrictLogging {

  val userGroupsKey: TypedKey[List[UserGroup]] = TypedKey[List[UserGroup]]("userGroups")

  def config: CommonConfig = resources.commonConfig

  override def initialise(): Unit = ()

  override def hasPermissionTo[T](permissionContext: PermissionContext[T]): PrincipalFilter = { user: Principal =>
    val userGroups = user.attributes.get(userGroupsKey).toList.flatten

    permissionContext match {
      case PermissionContext(EditMetadata, _) => true
      case PermissionContext(DeleteImage, _) => userGroups.contains(BBCImagesArchivist)
      case PermissionContext(DeleteCrops, _) => true
      case PermissionContext(ShowPaid, _) => true
    }
  }

  // there are none of these right now so simply always return true
  override def visibilityFilterFor[T](permission: PermissionWithParameter[T],
                                      principal: Principal): VisibilityFilter[T] = {
    { _: T => true }
  }
}
