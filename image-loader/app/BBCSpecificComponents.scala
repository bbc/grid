import akka.actor.ActorSystem
import com.gu.mediaservice.lib.cleanup.{ComposedImageProcessor, ImageProcessor, MetadataCleaners}
import com.gu.mediaservice.lib.config.ImageProcessorLoader.{loadImageProcessor, parseConfigValue}
import com.gu.mediaservice.lib.config.{ImageProcessorLoader, MetadataStore, UsageRightsStore}
import com.typesafe.config.Config
import lib.ImageLoaderConfig
import play.api.ConfigLoader

import scala.concurrent.ExecutionContext


trait BBCSpecificComponents {
  val metaDataConfigStore: MetadataStore
  val usageRightsStore: UsageRightsStore
  val imageProcessor: ComposedImageProcessor
}

object BBCSpecificComponentsLoad {
  def getComponents(config: ImageLoaderConfig)(implicit ec: ExecutionContext = ExecutionContext.global) = {
    new BBCSpecificComponents {
      override val metaDataConfigStore: MetadataStore = {
        val metadataStore = MetadataStore(config.configBucket, config)

        metadataStore
      }


      override val usageRightsStore: UsageRightsStore = {
          val usageRightsConfigStore = UsageRightsStore(config.configBucket, config)
          usageRightsStore
      }

      val BBCMetadataCleaner = {
        val metadataConfig = metaDataConfigStore.tryGet
        val allPhotographers = metadataConfig.map(_.allPhotographers).getOrElse(Map())
        new MetadataCleaners(allPhotographers)
      }

      /*Load processor that are more dependent on side effects like S3 access*/
      def bbcImageProcessors(key: String): Option[ImageProcessor] = {
        key match {
          case "BBCMetadataCleaner" => return Some(BBCMetadataCleaner)
        }
        None
      }

      override val imageProcessor: ComposedImageProcessor = {
        val configuration = config.configuration
        val defaultProcessors = configuration
          .get[Seq[ImageProcessor]]("image.processors")(ImageProcessorLoader.imageProcessorsConfigLoader)

        val bbcProcessorsToLoad: List[String] = config.getStringSetFromProperties("image.processorsbbc").toList

        val bbcProcessors = bbcProcessorsToLoad.map(bbcImageProcessors).filter(_.isDefined).map(_.get)

        val processors = defaultProcessors ++ bbcProcessors
        ImageProcessor.compose("ImageConfigLoader-imageProcessor", processors:_*)
      }
    }
  }
}
