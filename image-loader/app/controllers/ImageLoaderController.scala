package controllers

import java.io.File
import java.net.URI
import com.drew.imaging.ImageProcessingException
import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.argo.model.Link
import com.gu.mediaservice.lib.auth._
import com.gu.mediaservice.lib.logging.{FALLBACK, GridLogging, LogMarker, RequestLoggingContext}
import com.gu.mediaservice.lib.{DateTimeUtils, ImageIngestOperations}
import com.gu.mediaservice.model.UnsupportedMimeTypeException
import lib.FailureResponse.Response
import lib.{FailureResponse, _}
import lib.imaging.{NoSuchImageExistsInS3, UserImageLoaderException}
import lib.storage.ImageLoaderStore
import model.{Projector, QuarantineUploader, StatusType, UploadStatus, UploadStatusRecord, Uploader}
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSClient
import play.api.mvc._
import model.upload.UploadRequest
import org.joda.time.DateTime

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

class ImageLoaderController(auth: Authentication,
                            downloader: Downloader,
                            store: ImageLoaderStore,
                            uploadStatusTable: UploadStatusTable,
                            notifications: Notifications,
                            config: ImageLoaderConfig,
                            uploader: Uploader,
                            quarantineUploader: Option[QuarantineUploader],
                            projector: Projector,
                            override val controllerComponents: ControllerComponents,
                            wSClient: WSClient)
                           (implicit val ec: ExecutionContext)
  extends BaseController with ArgoHelpers {

  private lazy val indexResponse: Result = {
    val indexData = Map("description" -> "This is the Loader Service")
    val indexLinks = List(
      Link("load", s"${config.rootUri}/images{?uploadedBy,identifiers,uploadTime,filename}"),
      Link("import", s"${config.rootUri}/imports{?uri,uploadedBy,identifiers,uploadTime,filename}")
    )
    respond(indexData, indexLinks)
  }

  def index: Action[AnyContent] = auth { indexResponse }

  def quarantineOrStoreImage(uploadRequest: UploadRequest)(implicit logMarker: LogMarker) = {
    quarantineUploader.map(_.quarantineFile(uploadRequest)).getOrElse(uploader.storeFile(uploadRequest))
  }

  def loadImage(uploadedBy: Option[String], identifiers: Option[String], uploadTime: Option[String], filename: Option[String]): Action[DigestedFile] =  {

    implicit val context: RequestLoggingContext = RequestLoggingContext(
      initialMarkers = Map(
        "requestType" -> "load-image",
        "uploadedBy" -> uploadedBy.getOrElse(FALLBACK),
        "identifiers" -> identifiers.getOrElse(FALLBACK),
        "uploadTime" -> uploadTime.getOrElse(FALLBACK),
        "filename" -> filename.getOrElse(FALLBACK)
      )
    )
    logger.info("loadImage request start")

    // synchronous write to file
    val tempFile = createTempFile("requestBody")
    logger.info("body parsed")
    val parsedBody = DigestBodyParser.create(tempFile)

    auth.async(parsedBody) { req =>
      val uploadStatus = if(config.uploadToQuarantineEnabled) StatusType.Pending else StatusType.Completed
      val uploadExpiry = Instant.now.getEpochSecond + config.uploadStatusExpiry.toSeconds
      val record = UploadStatusRecord(req.body.digest, filename, uploadedBy, uploadTime, identifiers, uploadStatus, None, uploadExpiry)
      val result = for {
        uploadRequest <- uploader.loadFile(
          req.body,
          req.user,
          uploadedBy,
          identifiers,
          DateTimeUtils.fromValueOrNow(uploadTime),
          filename.flatMap(_.trim.nonEmptyOpt),
          context.requestId)
        _ <- uploadStatusTable.setStatus(record)
        result <- quarantineOrStoreImage(uploadRequest)
      } yield result
      result.onComplete( _ => Try { deleteTempFile(tempFile) } )

      result map { r =>
        val result = Accepted(r).as(ArgoMediaType)
        logger.info("loadImage request end")
        result
      } recover {
        case NonFatal(e) =>
          logger.error("loadImage request ended with a failure", e)
          val response = e match {
            case e: UnsupportedMimeTypeException => FailureResponse.unsupportedMimeType(e, config.supportedMimeTypes)
            case e: ImageProcessingException => FailureResponse.notAnImage(e, config.supportedMimeTypes)
            case e: java.io.IOException => FailureResponse.badImage(e)
            case other => FailureResponse.internalError(other)
          }
          FailureResponse.responseToResult(response)
      }
    }
  }

  // Fetch
  def projectImageBy(imageId: String): Action[AnyContent] = {
    implicit val context: RequestLoggingContext = RequestLoggingContext(
      initialMarkers = Map(
        "imageId" -> imageId,
        "requestType" -> "image-projection"
      )
    )
    val tempFile = createTempFile(s"projection-$imageId")
    auth.async { _ =>
      val result= projector.projectS3ImageById(projector, imageId, tempFile, context.requestId)

      result.onComplete( _ => Try { deleteTempFile(tempFile) } )

      result.map {
        case Some(img) =>
          logger.info("image found")
          Ok(Json.toJson(img)).as(ArgoMediaType)
        case None =>
          val s3Path = "s3://" + config.imageBucket + "/" + ImageIngestOperations.fileKeyFromId(imageId)
          logger.info("image not found")
          respondError(NotFound, "image-not-found", s"Could not find image: $imageId in s3 at $s3Path")
      } recover {
        case _: NoSuchImageExistsInS3 => NotFound(Json.obj("imageId" -> imageId))
        case _ => InternalServerError(Json.obj("imageId" -> imageId))
      }

    }
  }

  def importImage(
                   uri: String,
                   uploadedBy: Option[String],
                   identifiers: Option[String],
                   uploadTime: Option[String],
                   filename: Option[String]
                 ): Action[AnyContent] = {
    auth.async { request =>
      implicit val context: RequestLoggingContext = RequestLoggingContext(
        initialMarkers = Map(
          "requestType" -> "import-image",
          "key-tier" -> request.user.accessor.tier.toString,
          "key-name" -> request.user.accessor.identity
        )
      )

      logger.info("importImage request start")
      val existingImageStatus = uploadStatusTable.getStatus(uri.split("/").last).map {
        case Some(Right(record)) => (record.uploadedBy, record.identifiers, record.fileName)
        case _ => (None, None, None)
      }.recover{case _ => (None, None, None)}

      val tempFile = createTempFile("download")
      val importResult = for {
        record <- existingImageStatus
        validUri <- Future { URI.create(uri) }
        digestedFile <- downloader.download(validUri, tempFile)
        uploadRequest <- uploader.loadFile(
          digestedFile,
          request.user,
          record._1.orElse(uploadedBy),
          record._2.orElse(identifiers),
          DateTimeUtils.fromValueOrNow(uploadTime),
          record._3.orElse(filename).flatMap(_.trim.nonEmptyOpt),
          context.requestId)
        result <- uploader.storeFile(uploadRequest)
      } yield {
        logger.info("importImage request end")
        // NB This return code (202) is explicitly required by s3-watcher
        // Anything else (eg 200) will be logged as an error. DAMHIKIJKOK.
        Right(Accepted(result).as(ArgoMediaType))
      }

      // under all circumstances, remove the temp files
      importResult.onComplete { _ =>
        Try { deleteTempFile(tempFile) }
      }

      // this is an unusual way of generating a result due to the need to put the error message both in the upload
      // status table and also provide it in the response to the client.
      importResult.recover {
        // convert exceptions to failure responses
        case e: UnsupportedMimeTypeException => Left(FailureResponse.unsupportedMimeType(e, config.supportedMimeTypes))
        case _: IllegalArgumentException => Left(FailureResponse.invalidUri)
        case e: UserImageLoaderException => Left(FailureResponse.badUserInput(e))
        case NonFatal(_) => Left(FailureResponse.failedUriDownload)
      }.flatMap { res =>
        // update the upload status with the error or completion
        val status = res match {
          case Left(Response(_, response)) => UploadStatus(StatusType.Failed, Some(s"${response.errorKey}: ${response.errorMessage}"))
          case Right(_) => UploadStatus(StatusType.Completed, None)
        }
        uploadStatusTable.updateStatus(uri.split("/").last, status).flatMap(_ => Future.successful(res))
      }.transform {
        // create a play result out of what has happened
        case Success(Right(result)) => Success(result)
        case Success(Left(failure)) => Success(FailureResponse.responseToResult(failure))
        case Failure(NonFatal(e)) => Success(FailureResponse.responseToResult(FailureResponse.internalError(e)))
        case Failure(other) => Failure(other)
      }
    }
  }

  // Find this a better home if used more widely
  implicit class NonEmpty(s: String) {
    def nonEmptyOpt: Option[String] = if (s.isEmpty) None else Some(s)
  }

  // To avoid Future _madness_, it is better to make temp files at the controller and pass them down,
  // then clear them up again at the end.  This avoids leaks.
  def createTempFile(prefix: String)(implicit logMarker: LogMarker): File = {
    val tempFile = File.createTempFile(prefix, "", config.tempDir)
    logger.info(s"Created temp file ${tempFile.getName} in ${config.tempDir}")
    tempFile
  }

  def deleteTempFile(tempFile: File)(implicit logMarker: LogMarker): Future[Unit] = Future {
    if (tempFile.delete()) {
      logger.info(s"Deleted temp file $tempFile")
    } else {
      logger.warn(s"Unable to delete temp file $tempFile in ${config.tempDir}")
    }
  }

}
