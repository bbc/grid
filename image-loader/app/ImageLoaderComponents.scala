import com.gu.mediaservice.lib.config.{MetadataStore, UsageRightsStore}
import com.gu.mediaservice.lib.imaging.ImageOperations
import com.gu.mediaservice.lib.play.GridComponents
import controllers.ImageLoaderController
import lib._
import lib.storage.ImageLoaderStore
import model.{ImageUploadOps, OptimisedPngOps}
import play.api.ApplicationLoader.Context
import router.Routes

class ImageLoaderComponents(context: Context) extends GridComponents(context) {
  final override lazy val config = new ImageLoaderConfig(configuration)

  final override val buildInfo = utils.buildinfo.BuildInfo

  val loaderStore = new ImageLoaderStore(config)
  val imageOperations = new ImageOperations(context.environment.rootPath.getAbsolutePath)

  val notifications = new Notifications(config)
  val downloader = new Downloader()
  val optimisedPngOps = new OptimisedPngOps(loaderStore, config)

  val metaDataConfigStore = MetadataStore(config.configBucket, config)
  metaDataConfigStore.scheduleUpdates(actorSystem.scheduler)

  val usageRightsConfigStore = UsageRightsStore(config.configBucket, config)
  usageRightsConfigStore.scheduleUpdates(actorSystem.scheduler)

  val imageUploadOps = new ImageUploadOps(metaDataConfigStore, usageRightsConfigStore, loaderStore, config, imageOperations, optimisedPngOps)

  val controller = new ImageLoaderController(auth, downloader, loaderStore, notifications, config, imageUploadOps, controllerComponents, wsClient)

  override lazy val router = new Routes(httpErrorHandler, controller, management)
}
