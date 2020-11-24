package model


import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.auth.Authentication
import com.gu.mediaservice.lib.auth.Authentication.Principal
import com.gu.mediaservice.lib.aws.{S3Object, UpdateMessage}
import com.gu.mediaservice.lib.imaging.ImageOperations
import com.gu.mediaservice.lib.logging.{LogMarker, Stopwatch, addLogMarkers}
import com.gu.mediaservice.lib.logging.MarkerMap
import com.gu.mediaservice.lib.resource.FutureResources._
import com.gu.mediaservice.model._
import lib.{ImageLoaderConfig, Notifications}
import lib.storage.SlingScannerStore
import net.logstash.logback.marker.LogstashMarker
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import com.gu.mediaservice.lib.formatting._

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}

case class toScanImageUploadOpsCfg(originalFileBucket: String)

case class QuarantineImageUploadOpsDependencies(
                                       config: toScanImageUploadOpsCfg,
                                       imageOps: ImageOperations,
                                       storeOriginalFile: UploadRequest => Future[S3Object])

object VirusScanner{   

  def toQuarantineUploadOpsCfg(config: ImageLoaderConfig): toScanImageUploadOpsCfg = {
    toScanImageUploadOpsCfg(config.quarantineBucket)
  }

  def fromQuarantineUploadRequestShared(uploadRequest: UploadRequest, deps: QuarantineImageUploadOpsDependencies)
                             (implicit ec: ExecutionContext, logMarker: LogMarker) = {

    import deps._

    Logger.info("Starting image ops")
    
      uploadAndStoreImageForVirusScanning(config,
        storeOriginalFile,
        uploadRequest,
        deps)
  }

  private def uploadAndStoreImageForVirusScanning(config: toScanImageUploadOpsCfg,
                                  storeOriginalFile: UploadRequest => Future[S3Object],
                                  uploadRequest: UploadRequest,
                                  deps: QuarantineImageUploadOpsDependencies,
                              )
                                 (implicit ec: ExecutionContext, logMarker: LogMarker) = {
    Logger.info("stored for quarantine file")
      println("uploading to virus scan")
      val sourceStoreFuture = storeOriginalFile(uploadRequest)
       val s3Source = for{
                        s3Source <- sourceStoreFuture
                      }yield (s3Source)
         s3Source.map(println)

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

}

class VirusScanner(val store: SlingScannerStore,
               val config: ImageLoaderConfig,
               val imageOps: ImageOperations,
               val notifications: Notifications)
              (implicit val ec: ExecutionContext) extends ArgoHelpers {


  import VirusScanner.{fromQuarantineUploadRequestShared, toQuarantineUploadOpsCfg, toMetaMap}


  def sendToQuarantine(uploadRequest: UploadRequest)
                        (implicit logMarker: LogMarker) = Future {
                          println("sending to quarantine")

    val sideEffectDependencies = QuarantineImageUploadOpsDependencies(toQuarantineUploadOpsCfg(config), imageOps, storeSource)
     fromQuarantineUploadRequestShared(uploadRequest, sideEffectDependencies)
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

  // def storeFile(uploadRequest: UploadRequest)
  //              (implicit ec:ExecutionContext,
  //               logMarker: LogMarker): Future[JsObject] = {

  //   Logger.info("Storing file")

  //   for {
  //     imageUpload <- fromUploadRequest(uploadRequest)
  //     updateMessage = UpdateMessage(subject = "image", image = Some(imageUpload.image))
  //     _ <- Future { notifications.publish(updateMessage) }
  //     // TODO: centralise where all these URLs are constructed
  //     uri = s"${config.apiUri}/images/${uploadRequest.imageId}"
  //   } yield {
  //     Json.obj("uri" -> uri)
  //   }
  // }

}