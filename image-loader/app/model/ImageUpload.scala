package model

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.auth.Authentication
import com.gu.mediaservice.lib.auth.Authentication.Principal
import com.gu.mediaservice.lib.aws.{S3Object, UpdateMessage}
import com.gu.mediaservice.lib.cleanup.{MetadataCleaners, SupplierProcessors}
import com.gu.mediaservice.lib.config.{MetadataStore, UsageRightsStore}
import com.gu.mediaservice.lib.formatting._
import com.gu.mediaservice.lib.imaging.ImageOperations
import com.gu.mediaservice.lib.logging.{LogMarker, Stopwatch, addLogMarkers}
import com.gu.mediaservice.lib.logging.MarkerMap
import com.gu.mediaservice.lib.metadata.{FileMetadataHelper, ImageMetadataConverter}
import com.gu.mediaservice.lib.resource.FutureResources._
import com.gu.mediaservice.model._
import lib.{DigestedFile, ImageLoaderConfig, Notifications}
import lib.imaging.{FileMetadataReader, MimeTypeDetection}
import lib.storage.ImageLoaderStore
import net.logstash.logback.marker.LogstashMarker
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process._

case class OptimisedPng(optimisedFileStoreFuture: Future[Option[S3Object]], isPng24: Boolean,
                        optimisedTempFile: Option[File])

case object OptimisedPng {

  def shouldOptimise(mimeType: Option[MimeType], fileMetadata: FileMetadata): Boolean = {
    mimeType match {
      case Some(Png) =>
        fileMetadata.colourModelInformation.get("colorType") match {
          case Some("True Color") => true
          case Some("True Color with Alpha") => true
          case _ => false
        }
      case Some(Tiff) => false
      case _ => false
    }
  }
}

class OptimisedPngOps(store: ImageLoaderStore, config: ImageLoaderConfig)(implicit val ec: ExecutionContext, val logMaker: LogMarker = MarkerMap()) {
  private def storeOptimisedPng(uploadRequest: UploadRequest, optimisedPngFile: File) = store.storeOptimisedPng(
    uploadRequest.imageId,
    optimisedPngFile
  )

  private def isTransformedFilePath(filePath: String) = filePath.contains("transformed-")

  def build (file: File, uploadRequest: UploadRequest, fileMetadata: FileMetadata, speed: Double = 4): OptimisedPng = {
    if (OptimisedPng.shouldOptimise(uploadRequest.mimeType, fileMetadata)) {

      val optimisedFile = {
        val optimisedFilePath = config.tempDir.getAbsolutePath + "/optimisedpng-" + uploadRequest.imageId + ".png"
        Seq("pngquant", "--quality", "1-85", "--speed", speed.toString, file.getAbsolutePath, "--output", optimisedFilePath).!
        new File(optimisedFilePath)
      }
      if (isTransformedFilePath(file.getAbsolutePath)) {
        file.delete
      }

      if(optimisedFile.exists()) {  //may fail due to poor quality
        val pngStoreFuture: Future[Option[S3Object]] = Some(storeOptimisedPng(uploadRequest, optimisedFile))
          .map(result => result.map(Option(_)))
          .getOrElse(Future.successful(None))

        return OptimisedPng(pngStoreFuture, isPng24 = true, Some(optimisedFile))
      } else {
        Logger.warn("Failed to pngquant optimise image")(uploadRequest.toLogMarker)
      }
    }
    OptimisedPng(Future(None), isPng24 = false, None)
  }
}

object OptimisedPngOps {

  def build(file: File,
            uploadRequest: UploadRequest,
            fileMetadata: FileMetadata,
            config: ImageUploadOpsCfg,
            storeOrProject: (UploadRequest, File) => Future[S3Object])
           (implicit ec: ExecutionContext, logMarker: LogMarker): OptimisedPng = {

    val result = if (!OptimisedPng.shouldOptimise(uploadRequest.mimeType, fileMetadata)) {
      OptimisedPng(Future(None), isPng24 = false, None)
    } else {
      val optimisedFile: File = toOptimisedFile(file, uploadRequest, config)
      val pngStoreFuture: Future[Option[S3Object]] = Some(storeOrProject(uploadRequest, optimisedFile))
        .map(result => result.map(Option(_)))
        .getOrElse(Future.successful(None))

      if (isTransformedFilePath(file.getAbsolutePath))
        file.delete

      OptimisedPng(pngStoreFuture, isPng24 = true, Some(optimisedFile))
    }
    result
  }

  private def toOptimisedFile(file: File, uploadRequest: UploadRequest, config: ImageUploadOpsCfg)
                             (implicit logMarker: LogMarker): File = {
    val optimisedFilePath = config.tempDir.getAbsolutePath + "/optimisedpng - " + uploadRequest.imageId + ".png"
    Stopwatch("pngquant") {
      Seq("pngquant", "--quality", "1-85", file.getAbsolutePath, "--output", optimisedFilePath).!
    }
    new File(optimisedFilePath)
  }

  private def isTransformedFilePath(filePath: String): Boolean = filePath.contains("transformed-")

}

case class ImageUpload(uploadRequest: UploadRequest, image: Image)
case object ImageUpload {

  def createImage(uploadRequest: UploadRequest, source: Asset, originalSource: Option[Asset], thumbnail: Asset, png: Option[Asset],
                  fileMetadata: FileMetadata, metadata: ImageMetadata): Image = {
    val usageRights = NoRights
    Image(
      uploadRequest.imageId,
      uploadRequest.uploadTime,
      uploadRequest.uploadedBy,
      Some(uploadRequest.uploadTime),
      uploadRequest.identifiers,
      uploadRequest.uploadInfo,
      source,
      originalSource,
      Some(thumbnail),
      png,
      fileMetadata,
      None,
      metadata,
      metadata,
      usageRights,
      usageRights,
      List(),
      List()
    )
  }
}

class ImageUploadOps(metadataStore: MetadataStore,
                     usageRightsStore: UsageRightsStore,
                     loaderStore: ImageLoaderStore,
                     config: ImageLoaderConfig,
                     imageOps: ImageOperations,
                     optimisedPngOps: OptimisedPngOps)(implicit val ec: ExecutionContext, val logMaker: LogMarker = MarkerMap()) {

  import Uploader.initStores

  initStores(metadataStore, usageRightsStore)

  private def getMetaDataStore(): MetadataStore = metadataStore
  private def getUsageRightsDataStore(): UsageRightsStore = usageRightsStore

  def fromUploadRequest(uploadRequest: UploadRequest): Future[ImageUpload] = {
    Logger.info("Starting image ops")(uploadRequest.toLogMarker)
    val uploadedFile = uploadRequest.tempFile

    val fileMetadataFuture = uploadRequest.mimeType match {
      case Some(Png) => FileMetadataReader.fromICPTCHeadersWithColorInfo(uploadedFile, uploadRequest.imageId, uploadRequest.mimeType.get)
      case Some(Tiff) => FileMetadataReader.fromICPTCHeadersWithColorInfo(uploadedFile, uploadRequest.imageId, uploadRequest.mimeType.get)
      case _ => FileMetadataReader.fromIPTCHeaders(uploadedFile, uploadRequest.imageId)
    }
    val uploadMarkers = uploadRequest.toLogMarker
    Logger.info("Have read file headers")(uploadMarkers)

    fileMetadataFuture.flatMap(fileMetadata => {
      val markers: LogstashMarker = fileMetadata.toLogMarker.and(uploadMarkers)
      Logger.info("Have read file metadata")(markers)

      // These futures are started outside the for-comprehension, otherwise they will not run in parallel
      val sourceStoreFuture = storeSource(uploadRequest)
      Logger.info("stored source file")(uploadRequest.toLogMarker)
      // FIXME: pass mimeType
      val colourModelFuture = ImageOperations.identifyColourModel(uploadedFile, Jpeg)
      val sourceDimensionsFuture = FileMetadataReader.dimensions(uploadedFile, uploadRequest.mimeType)

      val thumbFuture = for {
        fileMetadata <- fileMetadataFuture
        colourModel <- colourModelFuture
        iccColourSpace = FileMetadataHelper.normalisedIccColourSpace(fileMetadata)
        thumb <- imageOps.createThumbnail(uploadedFile, uploadRequest.mimeType, config.thumbWidth, config.thumbQuality, config.tempDir, iccColourSpace, colourModel)
      } yield thumb

      Logger.info("thumbnail created")(uploadRequest.toLogMarker)

      //Could potentially use this file as the source file if needed (to generate thumbnail etc from)
      val toOptimiseFileFuture: Future[File] = uploadRequest.mimeType match {
        case Some(mime) => mime match {
          case transcodedMime if config.transcodedMimeTypes.contains(mime) =>
            for {
              transformedImage <- imageOps.transformImage(uploadedFile, uploadRequest.mimeType, config.tempDir, config.transcodedOptimisedQuality)
            } yield transformedImage
          case _ =>
            Future.apply(uploadedFile)
        }
        case _ =>
          Future.apply(uploadedFile)
      }

      toOptimiseFileFuture.flatMap(toOptimiseFile => {
        Logger.info("optimised image created")(uploadRequest.toLogMarker)

        val optimisedPng = optimisedPngOps.build(toOptimiseFile, uploadRequest, fileMetadata, config.optimiseSpeed)

        bracket(thumbFuture)(_.delete) { thumb =>
          // Run the operations in parallel
          val thumbStoreFuture = storeThumbnail(uploadRequest, thumb)
          val thumbDimensionsFuture = FileMetadataReader.dimensions(thumb, Some(Jpeg))

          for {
            s3Source <- sourceStoreFuture
            s3Thumb <- thumbStoreFuture
            s3PngOption <- optimisedPng.optimisedFileStoreFuture
            sourceDimensions <- sourceDimensionsFuture
            thumbDimensions <- thumbDimensionsFuture
            fileMetadata <- fileMetadataFuture
            colourModel <- colourModelFuture
            fullFileMetadata = fileMetadata.copy(colourModel = colourModel)

            metaDataConfig = metadataStore.get
            metadataCleaners = new MetadataCleaners(metaDataConfig.allPhotographers)
            metadata = ImageMetadataConverter.fromFileMetadata(fullFileMetadata)
            cleanMetadata = metadataCleaners.clean(metadata)

            sourceAsset = Asset.fromS3Object(s3Source, sourceDimensions)
            thumbAsset = Asset.fromS3Object(s3Thumb, thumbDimensions)

            pngAsset = if (optimisedPng.isPng24)
              Some(Asset.fromS3Object(s3PngOption.get, sourceDimensions))
            else
              None

            usageRightsConfig = usageRightsStore.get

            baseImage = ImageUpload.createImage(uploadRequest, sourceAsset, None, thumbAsset, pngAsset, fullFileMetadata, cleanMetadata)
            processedImage = new SupplierProcessors(metaDataConfig).process(baseImage, usageRightsConfig)

            // FIXME: dirty hack to sync the originalUsageRights and originalMetadata as well
            finalImage = processedImage.copy(
              originalMetadata = processedImage.metadata,
              originalUsageRights = processedImage.usageRights
            )
          } yield {
            if (optimisedPng.isPng24) optimisedPng.optimisedTempFile.get.delete
            Logger.info("Ending image ops")(uploadRequest.toLogMarker)
            ImageUpload(uploadRequest, finalImage)
          }
        }
      })
    })
  }

  def storeSource(uploadRequest: UploadRequest) = {
    val baseMeta = Map(
      "uploaded_by" -> uploadRequest.uploadedBy,
      "upload_time" -> printDateTime(uploadRequest.uploadTime)
    ) ++ uploadRequest.identifiersMeta

    val meta = uploadRequest.uploadInfo.filename match {
      case Some(f) => baseMeta ++ Map("file_name" -> URLEncoder.encode(f, StandardCharsets.UTF_8.name()))
      case _ => baseMeta
    }

    loaderStore.storeOriginal(
      uploadRequest.imageId,
      uploadRequest.tempFile,
      uploadRequest.mimeType,
      meta
    )
  }
  def storeThumbnail(uploadRequest: UploadRequest, thumbFile: File)
                    (implicit logMaker: LogMarker = MarkerMap()) = loaderStore.storeThumbnail(
    uploadRequest.imageId,
    thumbFile,
    Some(Jpeg)
  )
}
case class ImageUploadOpsCfg(
                              tempDir: File,
                              thumbWidth: Int,
                              thumbQuality: Double,
                              transcodedMimeTypes: List[MimeType],
                              originalFileBucket: String,
                              thumbBucket: String
                            )

case class ImageUploadOpsDependencies(
                                       config: ImageUploadOpsCfg,
                                       imageOps: ImageOperations,
                                       storeOrProjectOriginalFile: UploadRequest => Future[S3Object],
                                       storeOrProjectThumbFile: (UploadRequest, File) => Future[S3Object],
                                       storeOrProjectOptimisedPNG: (UploadRequest, File) => Future[S3Object],
                                       storeOrProjectSourceFile: UploadRequest => Future[S3Object]
                                     )

object Uploader {



  def toImageUploadOpsCfg(config: ImageLoaderConfig): ImageUploadOpsCfg = {
    ImageUploadOpsCfg(
      config.tempDir,
      config.thumbWidth,
      config.thumbQuality,
      config.transcodedMimeTypes,
      config.imageBucket,
      config.thumbnailBucket
    )
  }


  case class Stores(metadataStore: MetadataStore, usageRightsStore: UsageRightsStore)

  var stores : Stores = null

  def initStores(metadataStore: MetadataStore, usageRightsStore: UsageRightsStore): Stores = {
    stores = Stores(metadataStore, usageRightsStore)
    stores
  }

  def fromUploadRequestShared(uploadRequest: UploadRequest, deps: ImageUploadOpsDependencies)
                             (implicit ec: ExecutionContext, logMarker: LogMarker): Future[Image] = {

    import deps._

    Logger.info("Starting image ops")
    val uploadedFile = uploadRequest.tempFile

    val fileMetadataFuture = toFileMetadata(uploadedFile, uploadRequest.imageId, uploadRequest.mimeType)

    Logger.info("Have read file headers")

    fileMetadataFuture.flatMap(fileMetadata => {
      uploadAndStoreImage(config,
        storeOrProjectOriginalFile,
        storeOrProjectThumbFile,
        storeOrProjectOptimisedPNG,
        storeOrProjectSourceFile,
        uploadRequest,
        deps,
        uploadedFile,
        fileMetadataFuture,
        fileMetadata)
//      (ec, addLogMarkers(fileMetadata.toLogMarker))
    })
  }


  //metadataStore: MetadataStore,
  //usageRightsStore: UsageRightsStore,
  private def uploadAndStoreImage(config: ImageUploadOpsCfg,
                                  storeOrProjectOriginalFile: UploadRequest => Future[S3Object],
                                  storeOrProjectThumbFile: (UploadRequest, File) => Future[S3Object],
                                  storeOrProjectOptimisedPNG: (UploadRequest, File) => Future[S3Object],
                                  storeOrProjectSourceFile: UploadRequest => Future[S3Object],
                                  uploadRequest: UploadRequest,
                                  deps: ImageUploadOpsDependencies,
                                  uploadedFile: File,
                                  fileMetadataFuture: Future[FileMetadata],
                                  fileMetadata: FileMetadata)
                                 (implicit ec: ExecutionContext, logMarker: LogMarker) = {
    Logger.info("Have read file metadata")
    Logger.info("stored source file")
    // FIXME: pass mimeType
    val colourModelFuture = ImageOperations.identifyColourModel(uploadedFile, uploadRequest.mimeType.getOrElse(Jpeg))
    val sourceDimensionsFuture = FileMetadataReader.dimensions(uploadedFile, uploadRequest.mimeType)

    //If file is to be optimised, store source file. Source file was not stored for media/tiff on prior versions.
    val sourceStoreFileFutureOption = uploadRequest.mimeType match {
      case Some(mime) if config.transcodedMimeTypes.contains(mime) =>
        Some(storeOrProjectSourceFile(uploadRequest))
      case _ => None
    }

    //Convert Option[Future[_] to Future[Option[_]]
    val sourceStoreFileOptionFuture = sourceStoreFileFutureOption.map(_.map(Some(_))).getOrElse(Future.successful(None))

    val sourceFile = uploadRequest.tempFile
    // if the file needs pre-processing into a supported type of file, do it now and create the new upload request.
    createOptimisedFileFuture(uploadRequest, deps).flatMap(uploadRequest => {
      val sourceStoreFuture = storeOrProjectOriginalFile(uploadRequest)
      val toOptimiseFile = uploadRequest.tempFile
      val thumbFuture = createThumbFuture(fileMetadataFuture, colourModelFuture, uploadRequest, deps)
      Logger.info("thumbnail created")

      //problematic code is here: toOptimiseFile
      val optimisedPng = OptimisedPngOps.build(
        toOptimiseFile,
        uploadRequest,
        fileMetadata,
        config,
        storeOrProjectOptimisedPNG)(ec, logMarker)
      Logger.info(s"optimised image ($toOptimiseFile) created")

      bracket(thumbFuture)(_.delete) { thumb =>
        // Run the operations in parallel
        val thumbStoreFuture = storeOrProjectThumbFile(uploadRequest, thumb)
        val thumbDimensionsFuture = FileMetadataReader.dimensions(thumb, uploadRequest.mimeType)

        val finalImage = toFinalImage(
          stores.metadataStore,
          stores.usageRightsStore,
          sourceStoreFuture,
          thumbStoreFuture,
          sourceDimensionsFuture,
          thumbDimensionsFuture,
          fileMetadataFuture,
          colourModelFuture,
          optimisedPng,
          sourceStoreFileOptionFuture,
          uploadRequest,
        )
        Logger.info(s"Deleting temp file ${uploadedFile.getAbsolutePath}")
        uploadedFile.delete()
        toOptimiseFile.delete()
        //Delete source file if some optimization was done
        sourceStoreFileFutureOption.map(_ => sourceFile.delete())

        finalImage
      }
    })
  }

  def toMetaMap(uploadRequest: UploadRequest): Map[String, String] = {
    val baseMeta = Map(
      "uploaded_by" -> uploadRequest.uploadedBy,
      "upload_time" -> printDateTime(uploadRequest.uploadTime)
    ) ++ uploadRequest.identifiersMeta

    uploadRequest.uploadInfo.filename match {
      case Some(f) => baseMeta ++ Map("file_name" -> URLEncoder.encode(f, StandardCharsets.UTF_8.name()))
      case _ => baseMeta
    }
  }

  private def toFinalImage(metadataStore: MetadataStore,
                           usageRightsStore: UsageRightsStore,
                           sourceStoreFuture: Future[S3Object],
                           thumbStoreFuture: Future[S3Object],
                           sourceDimensionsFuture: Future[Option[Dimensions]],
                           thumbDimensionsFuture: Future[Option[Dimensions]],
                           fileMetadataFuture: Future[FileMetadata],
                           colourModelFuture: Future[Option[String]],
                           optimisedPng: OptimisedPng,
                           optionalSourceFileStorageFuture: Future[Option[S3Object]],
                           uploadRequest: UploadRequest)
                          (implicit ec: ExecutionContext, logMarker: LogMarker): Future[Image] = {
    Logger.info("Starting image ops")
    for {
      s3Source <- sourceStoreFuture
      s3Thumb <- thumbStoreFuture
      s3PngOption <- optimisedPng.optimisedFileStoreFuture
      sourceDimensions <- sourceDimensionsFuture
      thumbDimensions <- thumbDimensionsFuture
      fileMetadata <- fileMetadataFuture
      colourModel <- colourModelFuture
      optionalSourceFileStorage <- optionalSourceFileStorageFuture
      fullFileMetadata = fileMetadata.copy(colourModel = colourModel)

      metaDataConfig = metadataStore.get
      metadataCleaners = new MetadataCleaners(metaDataConfig.allPhotographers)
      metadata = ImageMetadataConverter.fromFileMetadata(fullFileMetadata)
      cleanMetadata = metadataCleaners.clean(metadata)
      originalSourceAsset = optionalSourceFileStorage.map(optionalSourceFile => Asset.fromS3Object(optionalSourceFile, sourceDimensions))
      sourceAsset = Asset.fromS3Object(s3Source, sourceDimensions)
      thumbAsset = Asset.fromS3Object(s3Thumb, thumbDimensions)

      pngAsset = if (optimisedPng.isPng24)
        Some(Asset.fromS3Object(s3PngOption.get, sourceDimensions))
      else
        None

      baseImage = ImageUpload.createImage(uploadRequest, sourceAsset, originalSourceAsset, thumbAsset, pngAsset, fullFileMetadata, cleanMetadata)

      usageRightsConfig = usageRightsStore.get
      processedImage = new SupplierProcessors(metaDataConfig).process(baseImage, usageRightsConfig)

      // FIXME: dirty hack to sync the originalUsageRights and originalMetadata as well
      finalImage = processedImage.copy(
        originalMetadata = processedImage.metadata,
        originalUsageRights = processedImage.usageRights
      )
    } yield {
      if (optimisedPng.isPng24) optimisedPng.optimisedTempFile.get.delete
      Logger.info("Ending image ops")
      finalImage
    }
  }

  private def toFileMetadata(f: File, imageId: String, mimeType: Option[MimeType]): Future[FileMetadata] = {
    mimeType match {
      case Some(Png | Tiff) => FileMetadataReader.fromICPTCHeadersWithColorInfo(f, imageId, mimeType.get)
      case _ => FileMetadataReader.fromIPTCHeaders(f, imageId)
    }
  }

  private def createThumbFuture(fileMetadataFuture: Future[FileMetadata],
                                colourModelFuture: Future[Option[String]],
                                uploadRequest: UploadRequest,
                                deps: ImageUploadOpsDependencies)(implicit ec: ExecutionContext) = {
    import deps._
    for {
      fileMetadata <- fileMetadataFuture
      colourModel <- colourModelFuture
      iccColourSpace = FileMetadataHelper.normalisedIccColourSpace(fileMetadata)
      thumb <- imageOps
        .createThumbnail(uploadRequest.tempFile, uploadRequest.mimeType, config.thumbWidth,
          config.thumbQuality, config.tempDir, iccColourSpace, colourModel)
    } yield thumb
  }

  private def createOptimisedFileFuture(uploadRequest: UploadRequest,
                                        deps: ImageUploadOpsDependencies)(implicit ec: ExecutionContext): Future[UploadRequest] = {
    import deps._
    uploadRequest.mimeType match {
      case Some(mime) if config.transcodedMimeTypes.contains(mime) =>
        for {
          transformedImage <- imageOps.transformImage(uploadRequest.tempFile, uploadRequest.mimeType, config.tempDir)
        } yield uploadRequest
          // This file has been converted.
          .copy(tempFile = transformedImage)
      case _ =>
        Future.successful(uploadRequest)
    }
  }


}


class Uploader(val store: ImageLoaderStore,
               val config: ImageLoaderConfig,
               val imageOps: ImageOperations,
               val notifications: Notifications)
              (implicit val ec: ExecutionContext) extends ArgoHelpers {


  import Uploader.{fromUploadRequestShared, toMetaMap, toImageUploadOpsCfg}


  def fromUploadRequest(uploadRequest: UploadRequest)
                       (implicit logMarker: LogMarker): Future[ImageUpload] = {

    val sideEffectDependencies = ImageUploadOpsDependencies(toImageUploadOpsCfg(config), imageOps,
      storeSource, storeThumbnail, storeOptimisedPng, storeSourceFile)

    val finalImage = fromUploadRequestShared(uploadRequest, sideEffectDependencies)

    finalImage.map(img => Stopwatch("finalImage"){ImageUpload(uploadRequest, img)})
  }

  private def storeSource(uploadRequest: UploadRequest)
                         (implicit logMarker: LogMarker) = {
    val meta = toMetaMap(uploadRequest)
    store.storeOriginal(
      uploadRequest.imageId,
      uploadRequest.tempFile,
      uploadRequest.mimeType,
      meta
    )
  }

  private def storeThumbnail(uploadRequest: UploadRequest, thumbFile: File)
                            (implicit logMarker: LogMarker) = store.storeThumbnail(
    uploadRequest.imageId,
    thumbFile,
    Some(uploadRequest.mimeType.getOrElse(Jpeg))
  )

  private def storeSourceFile(uploadRequest: UploadRequest)
                             (implicit logMarker: LogMarker) = store.storeSource(
    uploadRequest.imageId,
    uploadRequest.tempFile,
    uploadRequest.mimeType
  )

  private def storeOptimisedPng(uploadRequest: UploadRequest, optimisedPngFile: File)
                               (implicit logMarker: LogMarker) = {
    store.storeOptimisedPng(
      uploadRequest.imageId,
      optimisedPngFile
    )
  }

  def loadFile(digestedFile: DigestedFile,
               user: Principal,
               uploadedBy: Option[String],
               identifiers: Option[String],
               uploadTime: DateTime,
               filename: Option[String],
               requestId: UUID)
              (implicit ec:ExecutionContext,
               logMarker: LogMarker): Future[UploadRequest] = Future {
    val DigestedFile(tempFile, id) = digestedFile

    // TODO: should error if the JSON parsing failed
    val identifiersMap = identifiers.map(Json.parse(_).as[Map[String, String]]) getOrElse Map()

    MimeTypeDetection.guessMimeType(tempFile) match {
      case util.Left(unsupported) =>
        Logger.error(s"Unsupported mimetype", unsupported)
        throw unsupported
      case util.Right(mimeType) =>
        Logger.info(s"Detected mimetype as $mimeType")
        UploadRequest(
          requestId = requestId,
          imageId = id,
          tempFile = tempFile,
          mimeType = Some(mimeType),
          uploadTime = uploadTime,
          uploadedBy = uploadedBy.getOrElse(Authentication.getIdentity(user)),
          identifiers = identifiersMap,
          uploadInfo = UploadInfo(filename)
        )
    }
  }

  def storeFile(uploadRequest: UploadRequest)
               (implicit ec:ExecutionContext,
                logMarker: LogMarker): Future[JsObject] = {

    Logger.info("Storing file")

    for {
      imageUpload <- fromUploadRequest(uploadRequest)
      updateMessage = UpdateMessage(subject = "image", image = Some(imageUpload.image))
      _ <- Future { notifications.publish(updateMessage) }
      // TODO: centralise where all these URLs are constructed
      uri = s"${config.apiUri}/images/${uploadRequest.imageId}"
    } yield {
      Json.obj("uri" -> uri)
    }
  }

}


