package lib

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.kinesis.metrics.interfaces.MetricsLevel
import com.gu.mediaservice.lib.aws.AwsClientBuilderUtils
import com.gu.mediaservice.lib.config.CommonConfig
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.Configuration

case class KinesisReceiverConfig(
  override val awsRegion: String,
  override val awsCredentials: AWSCredentialsProvider,
  override val awsLocalEndpoint: Option[String],
  streamName: String,
  rewindFrom: Option[DateTime],
  metricsLevel: MetricsLevel = MetricsLevel.DETAILED
) extends AwsClientBuilderUtils

object KinesisReceiverConfig {
  def apply(streamName: String, rewindFrom: Option[DateTime], thrallConfig: ThrallConfig): KinesisReceiverConfig = KinesisReceiverConfig(
    thrallConfig.awsRegion,
    thrallConfig.awsCredentials,
    thrallConfig.awsLocalEndpoint,
    streamName,
    rewindFrom
  )
}

class ThrallConfig(override val configuration: Configuration) extends CommonConfig {
  final override lazy val appName = "thrall"

  lazy val queueUrl: String = properties("sqs.queue.url")

  lazy val imageBucket: String = properties("s3.image.bucket")
  lazy val quarantineBucket: String = properties("s3.quarantine.bucket")
  lazy val writeAlias: String = properties.getOrElse("es.index.aliases.write", configuration.get[String]("es.index.aliases.write"))

  lazy val thumbnailBucket: String = properties("s3.thumb.bucket")

  lazy val elasticsearch7Url: String =  properties("es7.url")
  lazy val elasticsearch7Cluster: String = properties("es7.cluster")
  lazy val elasticsearch7Shards: Int = properties("es7.shards").toInt
  lazy val elasticsearch7Replicas: Int = properties("es7.replicas").toInt

  lazy val metadataTopicArn: String = properties("indexed.image.sns.topic.arn")

  lazy val rewindFrom: Option[DateTime] = properties.get("thrall.kinesis.stream.rewindFrom").map(ISODateTimeFormat.dateTime.parseDateTime)
  lazy val lowPriorityRewindFrom: Option[DateTime] = properties.get("thrall.kinesis.lowPriorityStream.rewindFrom").map(ISODateTimeFormat.dateTime.parseDateTime)

  lazy val isVersionedS3: Boolean = properties.getOrElse("s3.image.versioned", "false").toBoolean
  lazy val from: Option[DateTime] = properties.get("rewind.from").map(ISODateTimeFormat.dateTime.parseDateTime)

  def kinesisConfig: KinesisReceiverConfig = KinesisReceiverConfig(thrallKinesisStream, rewindFrom, this)
  def kinesisLowPriorityConfig: KinesisReceiverConfig = KinesisReceiverConfig(thrallKinesisLowPriorityStream, lowPriorityRewindFrom, this)
}
