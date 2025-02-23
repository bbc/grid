package com.gu.mediaservice.lib

import com.gu.mediaservice.lib.aws.S3
import com.gu.mediaservice.lib.config.CommonConfig
import com.gu.mediaservice.lib.logging.{GridLogging, LogMarker}
import com.gu.mediaservice.model.MimeType
import org.slf4j.LoggerFactory

import java.io.File
import scala.jdk.CollectionConverters._
import scala.concurrent.Future

// TODO: If deleteObject fails - we should be catching the errors here to avoid them bubbling to the application
class S3ImageStorage(config: CommonConfig) extends S3(config) with ImageStorage with GridLogging {

  private val cacheSetting = Some(cacheForever)
  def storeImage(bucket: String, id: String, file: File, mimeType: Option[MimeType],
                 meta: Map[String, String] = Map.empty, overwrite: Boolean)
                (implicit logMarker: LogMarker) = {
    logger.info(logMarker, s"bucket: $bucket, id: $id, meta: $meta")
    val eventualObject = if (overwrite) {
      store(bucket, id, file, mimeType, meta, cacheSetting)
    } else {
      storeIfNotPresent(bucket, id, file, mimeType, meta, cacheSetting)
    }
    eventualObject.onComplete(o => logger.info(logMarker, s"storeImage completed $o"))
    eventualObject
  }

  def deleteImage(bucket: String, id: String)(implicit logMarker: LogMarker) = Future {
    client.deleteObject(bucket, id)
    logger.info(logMarker, s"Deleted image $id from bucket $bucket")
  }

  def deleteVersionedImage(bucket: String, id: String)(implicit logMarker: LogMarker) = Future {
    val objectVersion = client.getObjectMetadata(bucket, id).getVersionId
    client.deleteVersion(bucket, id, objectVersion)
    logger.info(logMarker, s"Deleted image $id from bucket $bucket (version: $objectVersion)")
  }

  def deleteFolder(bucket: String, id: String)(implicit logMarker: LogMarker) = Future {
		val files = client.listObjects(bucket, id).getObjectSummaries.asScala
		files.foreach(file => client.deleteObject(bucket, file.getKey))
		logger.info(logMarker, s"Deleting images in folder $id from bucket $bucket")
	}

}
