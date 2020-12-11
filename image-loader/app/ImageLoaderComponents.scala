import akka.actor.ActorSystem
import com.gu.mediaservice.lib.config.{MetadataStore, UsageRightsStore}
import com.gu.mediaservice.lib.imaging.ImageOperations
import com.gu.mediaservice.lib.play.GridComponents
import controllers.ImageLoaderController
import lib._
import lib.storage.ImageLoaderStore
import model.{ImageUploadOps, OptimisedPngOps, Projector, Uploader}
import play.api.ApplicationLoader.Context
import router.Routes

import scala.concurrent.ExecutionContext

class ImageLoaderComponents(context: Context)(implicit ec: ExecutionContext = ExecutionContext.global) extends GridComponents(context) {
  final override lazy val config = new ImageLoaderConfig(configuration)

  final override val buildInfo = utils.buildinfo.BuildInfo

  val loaderStore = new ImageLoaderStore(config)
  val imageOperations = new ImageOperations(context.environment.rootPath.getAbsolutePath)

  val notifications = new Notifications(config)
  val downloader = new Downloader()
  val uploader = new Uploader(loaderStore, config, imageOperations, notifications)

  val projector = Projector(config, imageOperations)

  val optimisedPngOps = new OptimisedPngOps(loaderStore, config)

  val bbcSpecificComponents = BBCSpecificComponentsLoad.getComponents(config)
  bbcSpecificComponents.metaDataConfigStore.scheduleUpdates(actorSystem.scheduler)
  val metaDataConfigStore = bbcSpecificComponents.metaDataConfigStore
  val usageRightsConfigStore = bbcSpecificComponents.usageRightsStore

  val imageUploadOps = new ImageUploadOps(loaderStore, config, imageOperations, optimisedPngOps, bbcSpecificComponents.imageProcessor)

  val controller = new ImageLoaderController(auth, downloader, loaderStore, notifications, config, uploader, projector, controllerComponents, wsClient)

  override lazy val router = new Routes(httpErrorHandler, controller, management)
}
