import com.gu.mediaservice.lib.config.MetadataConfig
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


  metaDataConfigStore.scheduleUpdates(actorSystem.scheduler)
  metaDataConfigStore.update()

  metaDataConfigStore.get.map {
    m: MetadataConfig => {
      println(m.freeSuppliers)
      println(m.suppliersCollectionExcl)
      println("Creative: ", m.getPhotographer("David Sillitoe"))
      println("Creative: ", m.getPhotographer("Christopher Thomond"))
    }
  }

  val imageUploadOps = new ImageUploadOps(metaDataConfigStore, loaderStore, config, imageOperations, optimisedPngOps)

  val controller = new ImageLoaderController(auth, downloader, loaderStore, notifications, config, imageUploadOps, controllerComponents, wsClient)

  override lazy val router = new Routes(httpErrorHandler, controller, management)
}
