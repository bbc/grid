package lib

import java.io.File

import com.gu.mediaservice.lib.cleanup.{ComposedImageProcessor, ImageProcessor}
import com.gu.mediaservice.lib.config.{CommonConfig, ImageProcessorLoader}
import com.gu.mediaservice.model._
import com.typesafe.scalalogging.StrictLogging
import play.api.Configuration

class ImageLoaderConfig(override val configuration: Configuration) extends CommonConfig {

  final override lazy val appName = "image-loader"

  val imageBucket: String = properties("s3.image.bucket")

  val quarantineBucket: String = properties("s3.quarantine.bucket")

  val thumbnailBucket: String = properties("s3.thumb.bucket")

  val configBucket: String = properties("s3.config.bucket")

  val uploadToQuarantineEnabled: Boolean = properties.getOrElse("upload.quarantine.enabled", "false").toLowerCase match {
    case "true" => true
    case _ => false
  }

  val tempDir: File = new File(properties.getOrElse("upload.tmp.dir", "/tmp"))

  val thumbWidth: Int = 256
  val thumbQuality: Double = 85d // out of 100

  val rootUri: String = services.loaderBaseUri
  val apiUri: String = services.apiBaseUri
  val loginUriTemplate: String = services.loginUriTemplate

  val transcodedMimeTypes: List[MimeType] = getStringSetFromProperties("transcoded.mime.types").toList.map(MimeType(_))
  val transcodedOptimisedQuality: Double = 10d // out of 100
  val optimiseSpeed: Double = 11d // out of 11
  val supportedMimeTypes = List(Jpeg, Png) //::: transcodedMimeTypes //TODO: Improve the transcoded mime types importation

  /**
    * Load in the chain of image processors from config. This can be a list of
    * companion objects, class names, both with and without config.
    * For example:
    * {{{
    * image.processors = [
    *   // simple class
    *   "com.gu.mediaservice.lib.cleanup.GuardianMetadataCleaners",
    *   // a companion object
    *   "com.gu.mediaservice.lib.cleanup.SupplierProcessors$",
    *   "com.yourdomain.YourImageProcessor",
    *   // a class with a single arg constructor taking a play Configuration object
    *   {
    *     className: "com.yourdomain.YourImageProcessorWithConfig"
    *     config: {
    *       configKey1: value1
    *     }
    *   }
    * ]
    * }}}
    *
    * Depending on the type it will be loaded differently using reflection. Companion objects will be looked up
    * and the singleton instance added to the list. Classes will be looked up and will be examined for an appropriate
    * constructor. The constructor can either be no-arg or have a single argument of `play.api.Configuration`.
    *
    * If configuration is specified but not used (a companion object or class with no arg constructor is specified)
    * then loading the image processor will fail so as to avoid configuration errors.
    */
  val imageProcessor: ComposedImageProcessor = {
    val processors = configuration
      .get[Seq[ImageProcessor]]("image.processors")(ImageProcessorLoader.imageProcessorsConfigLoader)
    ImageProcessor.compose("ImageConfigLoader-imageProcessor", processors:_*)
  }
}
