package controllers

import java.io._
import java.net.URI

import com.google.api.client.util.IOUtils
import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.argo.model.Link
import com.gu.mediaservice.lib.auth.Authentication.Principal
import com.gu.mediaservice.lib.auth._
import com.gu.mediaservice.lib.aws.UpdateMessage
import com.gu.mediaservice.lib.logging.GridLogger
import com.gu.mediaservice.model.UploadInfo
import lib._
import lib.imaging.MimeTypeDetection
import lib.storage.ImageLoaderStore
import model.{ImageUploadOps, UploadRequest}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class ImageLoaderController(auth: Authentication, downloader: Downloader, store: ImageLoaderStore, notifications: Notifications, config: ImageLoaderConfig, imageUploadOps: ImageUploadOps,
                            override val controllerComponents: ControllerComponents, wSClient: WSClient)(implicit val ec: ExecutionContext)
  extends BaseController with ArgoHelpers {

  val indexResponse: Result = {
    val indexData = Map("description" -> "This is the Loader Service")
    val indexLinks = List(
      Link("load",   s"${config.rootUri}/images{?uploadedBy,identifiers,uploadTime,filename}"),
      Link("import", s"${config.rootUri}/imports{?uri,uploadedBy,identifiers,uploadTime,filename}")
    )
    respond(indexData, indexLinks)
  }

  def index = auth { indexResponse }

  def createTempFile(prefix: String) = File.createTempFile(prefix, "", config.tempDir)

  def loadImage(uploadedBy: Option[String], identifiers: Option[String], uploadTime: Option[String], filename: Option[String]) =
    auth.async(DigestBodyParser.create(createTempFile("requestBody"))) { req =>
      val result = loadFile(uploadedBy, identifiers, uploadTime, filename)(req)
      result.onComplete { _ => req.body.file.delete() }

      result
    }

  def importImage(uri: String, uploadedBy: Option[String], identifiers: Option[String], uploadTime: Option[String], filename: Option[String]) =
    auth.async { request =>
      GridLogger.info(s"request to import an image", request.user.apiKey)
      Try(URI.create(uri)) map { validUri =>
        val tmpFile = createTempFile("download")

        val result = downloader.download(validUri, tmpFile).flatMap { digestedFile =>
          loadFile(digestedFile, request.user, uploadedBy, identifiers, uploadTime, filename)
        } recover {
          case NonFatal(e) =>
            Logger.error(s"Unable to download image $uri", e)
            failedUriDownload
        }

        result onComplete (_ => tmpFile.delete())
        result

      } getOrElse Future.successful(invalidUri)
    }

  def loadFile(digestedFile: DigestedFile, user: Principal,
               uploadedBy: Option[String], identifiers: Option[String],
               uploadTime: Option[String], filename: Option[String]): Future[Result] = {

    val DigestedFile(tempFile_, id_) = digestedFile

    val uploadedBy_ = uploadedBy match {
      case Some(by) => by
      case None => Authentication.getEmail(user)
    }

    // TODO: should error if the JSON parsing failed
    val identifiers_ = identifiers.map(Json.parse(_).as[Map[String, String]]) getOrElse Map()

    val uploadInfo_ = UploadInfo(filename.flatMap(_.trim.nonEmptyOpt))

    // TODO: handle the error thrown by an invalid string to `DateTime`
    // only allow uploadTime to be set by AuthenticatedService
    val uploadTime_ = uploadTime match {
      case Some(time) => new DateTime(time)
      case None => DateTime.now
    }

    // Abort early if unsupported mime-type
    val mimeType_ = MimeTypeDetection.guessMimeType(tempFile_)

    val uploadRequest = UploadRequest(
      id = id_,
      tempFile = tempFile_,
      mimeType = mimeType_,
      uploadTime = uploadTime_,
      uploadedBy = uploadedBy_,
      identifiers = identifiers_,
      uploadInfo = uploadInfo_
    )

    Logger.info(s"Received ${uploadRequestDescription(uploadRequest)}")

    val supportedMimeType = config.supportedMimeTypes.exists(mimeType_.contains(_))

    if (supportedMimeType) storeFile(uploadRequest) else convertFile(uploadRequest)
  }

  // Convenience alias
  private def loadFile(uploadedBy: Option[String], identifiers: Option[String], uploadTime: Option[String],
                       filename: Option[String])
                      (request: Authentication.Request[DigestedFile]): Future[Result] =
    loadFile(request.body, request.user, uploadedBy, identifiers, uploadTime, filename)


  def uploadRequestDescription(u: UploadRequest): String = {
    s"id: ${u.id}, by: ${u.uploadedBy} @ ${u.uploadTime}, mimeType: ${u.mimeType getOrElse "none"}, filename: ${u.uploadInfo.filename getOrElse "none"}"
  }

  val invalidUri        = respondError(BadRequest, "invalid-uri", s"The provided 'uri' is not valid")
  val failedUriDownload = respondError(BadRequest, "failed-uri-download", s"The provided 'uri' could not be downloaded")

  def unsupportedTypeError(u: UploadRequest): Future[Result] = Future {
    Logger.info(s"Rejected ${uploadRequestDescription(u)}: mime-type is not supported")
    val mimeType = u.mimeType getOrElse "none"

    respondError(
      UnsupportedMediaType,
      "unsupported-type",
      s"Unsupported mime-type: $mimeType. Supported: ${config.supportedMimeTypes.mkString(", ")}"
    )
  }

  def fileConversionError(u: UploadRequest): Future[Result] = Future {
    Logger.info(s"Rejected ${uploadRequestDescription(u)}: could not convert file into a png")

    respondError(
      UnsupportedMediaType,
      "unsupported-conversion-failed",
      s"Failed to convert unsupported media type into a supported media type. Please try uploading a supported media type instead:  ${config.supportedMimeTypes.mkString(", ")}"
    )
  }

  def storeFile(uploadRequest: UploadRequest): Future[Result] = {
    val result = for {
      imageUpload <- imageUploadOps.fromUploadRequest(uploadRequest)
      image = imageUpload.image
    } yield {
      val updateMessage = UpdateMessage(subject = "image", image = Some(image))
      notifications.publish(Json.toJson(image), "image", updateMessage)

      // TODO: centralise where all these URLs are constructed
      Accepted(Json.obj("uri" -> s"${config.apiUri}/images/${uploadRequest.id}")).as(ArgoMediaType)
    }

    result recover {
      case e =>
        Logger.warn(s"Rejected ${uploadRequestDescription(uploadRequest)}: ${e.getMessage}.", e)

        store.deleteOriginal(uploadRequest.id).onComplete {
          case Failure(err) => Logger.error(s"Failed to delete image for ${uploadRequest.id}: $err")
          case _ =>
        }
        respondError(BadRequest, "upload-error", e.getMessage)
    }
  }

  def convertFile(u: UploadRequest): Future[Result] = {
    Logger.info(s"Request ${uploadRequestDescription(u)}: mime-type is not supported, attempting to convert to supported type")

    //Slightly dodgy check to make sure the filename ends with a tif otherwise just fail
    if (!u.uploadInfo.filename.getOrElse("").toLowerCase.endsWith(".tif") && !u.uploadInfo.filename.getOrElse("").toLowerCase.endsWith(".tiff")) {
      Logger.info(s"Not attempting to convert file with name: ${u.uploadInfo.filename.getOrElse("")}, as it does not appear to be a tiff image")
      return unsupportedTypeError(u)
    }

    val uploadObjFuture = store.storeImage(config.transformationBucket, "in/" + u.id, u.tempFile, None)
    Logger.info(s"Storing unsupported file into s3 at: ${"in/" + u.id}")
    val uploadResult = Await.ready(uploadObjFuture, Duration.Inf).value.get
    uploadResult match {
      case Success(s3Object) =>
        Logger.info(s"S3 upload complete, got s3 object back: $s3Object")
      case Failure(exceptionType) => Logger.info(s"Got exception: ${exceptionType.getMessage}")
        return fileConversionError(u)
    }

    val pollObjFuture = store.pollForObject(config.transformationBucket, "out/" + u.id, 100, 3000)  //Poll for 3000*100ms = 5 mins
    Logger.info(s"Polling for converted file in s3 at: ${"out/" + u.id}")
    val fetchResult = Await.ready(pollObjFuture, Duration.Inf).value.get
    val transformedS3ObjOpt = fetchResult match {
      case Success(s3ObjectOpt) =>
        Logger.info(s"Poll complete, got s3 object back: $s3ObjectOpt")
        s3ObjectOpt
      case Failure(exceptionType) => Logger.info(s"Got exception: ${exceptionType.getMessage}")
        return fileConversionError(u)
    }

    transformedS3ObjOpt match {
      case Some(transformedS3Obj) => {
        Logger.info(s"Got transformed s3 object back: $transformedS3Obj")
        IOUtils.copy(transformedS3Obj.getObjectContent, new FileOutputStream(u.tempFile))

        val newMimeType = MimeTypeDetection.guessMimeType(u.tempFile)
        Logger.info(s"New MimeType for file: $newMimeType")
        val supportedMimeType = config.supportedMimeTypes.exists(newMimeType.contains(_))

        val newUploadRequest = u.copy(mimeType = newMimeType)

        if (supportedMimeType) storeFile(newUploadRequest) else fileConversionError(newUploadRequest)
      }
      case None => Logger.info(s"Could not find s3 object at path ${"in/" + u.id}")
        fileConversionError(u)
    }
  }


  // Find this a better home if used more widely
  implicit class NonEmpty(s: String) {
    def nonEmptyOpt: Option[String] = if (s.isEmpty) None else Some(s)
  }
}
