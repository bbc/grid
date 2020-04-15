package lib.kinesis

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration
import com.amazonaws.services.kinesis.metrics.interfaces.MetricsLevel
import com.amazonaws.services.kinesis.model.CreateStreamRequest
import com.gu.mediaservice.lib.aws.{AwsClientBuilderUtils, KinesisSenderConfig, ThrallMessageSender}
import com.whisk.docker.impl.spotify.DockerKitSpotify
import com.whisk.docker.scalatest.DockerTestKit
import com.whisk.docker.{DockerContainer, DockerKit, DockerReadyChecker}
import lib.KinesisReceiverConfig
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpec, Matchers}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Properties

trait KinesisTestBase extends FunSpec with BeforeAndAfterAll with Matchers with DockerKit with DockerTestKit with DockerKitSpotify with MockitoSugar with AwsClientBuilderUtils {
  private val localstackPort = 4566
  private val webUiPort = 5050

  override val awsLocalEndpoint: Option[String] = Some(Properties.envOrElse("LOCALSTACK_ENDPOINT", s"http://localhost:$localstackPort"))
  override val awsRegion = "eu-west-1"
  override val awsCredentials = new AWSStaticCredentialsProvider(new BasicAWSCredentials("stub", "creds"))

  val highPriorityStreamName = "thrall-test-stream-high-priority"
  val lowPriorityStreamName = "thrall-test-stream-low-priority"
  val streamNames = List(highPriorityStreamName, lowPriorityStreamName)

  private val useDockerForTests = Properties.envOrElse("USE_DOCKER_FOR_TESTS", "true").toBoolean

  private val localstackVersion = "0.11.0"

  private val localstackContainer = if (useDockerForTests) Some(DockerContainer(s"localstack/localstack:$localstackVersion")
    .withPorts(localstackPort -> Some(localstackPort), webUiPort -> Some(webUiPort))
    .withEnv(
      s"SERVICES=kinesis,dynamodb",
      s"PORT_WEB_UI=$webUiPort",
      s"DEFAULT_REGION=$webUiPort",
      "KINESIS_ERROR_PROBABILITY=0.0"
    )
    .withReadyChecker(
      DockerReadyChecker.HttpResponseCode(webUiPort, "health").within(1.minutes).looped(40, 1250.millis)
    )) else None

  final override val StartContainersTimeout = 1.minute

  final override def dockerContainers: List[DockerContainer] = localstackContainer.toList ++ super.dockerContainers

  private def createKinesisClient(): AmazonKinesis = withLocalAWSCredentials(AmazonKinesisClientBuilder.standard()).build()

  private def createStream(client: AmazonKinesis, name: String) = client.createStream(
    new CreateStreamRequest()
      .withStreamName(name)
      .withShardCount(1)
  )

  private def ensureStreamExistsAndIsActive(client: AmazonKinesis, streamNames: List[String]) = {
    val streamListResult = client.listStreams()
    streamNames.forall(streamName =>
      streamListResult.getStreamNames.contains(streamName) &&
        client.describeStream(streamName).getStreamDescription.getStreamStatus == "ACTIVE")
  }

  private def checkStreams(client: AmazonKinesis): Boolean = {
    if (ensureStreamExistsAndIsActive(client, streamNames)) {
      true
    } else {
      Thread.sleep(1000)
      checkStreams(client)
    }
  }

  override def beforeAll {
    super.beforeAll()

    val client = createKinesisClient()
    createStream(client, highPriorityStreamName)
    createStream(client, lowPriorityStreamName)

    val streamNames = client.listStreams().getStreamNames.asScala
    assert(streamNames.contains(highPriorityStreamName))
    assert(streamNames.contains(lowPriorityStreamName))

    // We must wait for the stream to be ready – see https://github.com/localstack/localstack/issues/231
    Await.result(Future { checkStreams(client) }, 10.seconds)
  }

  def getSenderConfig(streamName: String): ThrallMessageSender = new ThrallMessageSender(
    KinesisSenderConfig(awsRegion, awsCredentials, awsLocalEndpoint, streamName)
  )

  def getReceiverConfig(streamName: String): KinesisClientLibConfiguration  = {
    val config = new KinesisReceiverConfig(
      awsRegion,
      awsCredentials,
      awsLocalEndpoint,
      streamName,
      None,
      MetricsLevel.NONE
    )
    KinesisConfig.kinesisConfig(config)
  }
}
