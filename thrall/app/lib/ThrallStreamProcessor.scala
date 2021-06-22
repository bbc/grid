package lib

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.{GraphDSL, MergePreferred, MergePrioritized, Source}
import akka.stream.{Materializer, SourceShape}
import akka.{Done, NotUsed}
import com.contxt.kinesis.KinesisRecord
import com.gu.mediaservice.lib.DateTimeUtils
import com.gu.mediaservice.lib.aws.UpdateMessage
import com.gu.mediaservice.lib.logging._
import com.gu.mediaservice.model.ThrallMessage
import lib.kinesis.{MessageTranslator,ThrallEventConsumer}
import scala.concurrent.Future
import scala.util.{Failure, Success}

sealed trait Priority
case object AutomationPriority extends Priority {
  override def toString = "low"
}
case object ReingestionPriority extends Priority {
  override def toString = "lowest"
}
case object UiPriority extends Priority {
  override def toString = "high"
}

case class TaggedRecord[T](payload: T,
                           arrivalTimestamp: Instant,
                           priority: Priority,
                           markProcessed: () => Unit) extends LogMarker {
  override def markerContents: Map[String, Any] = (payload match {
    case withMarker:LogMarker => withMarker.markerContents
    case _ => Map()
  }) ++ Map(
    "recordPriority" -> priority.toString,
    "recordArrivalTime" -> DateTimeUtils.toString (arrivalTimestamp)
  )

  def map[V](f: T => V): TaggedRecord[V] = this.copy(payload = f(payload))
}

class ThrallStreamProcessor(
  uiSource: Source[KinesisRecord, Future[Done]],
  automationSource: Source[KinesisRecord, Future[Done]],
  reingestionSource: Source[(UpdateMessage, Instant), Future[Done]],
  consumer: ThrallEventConsumer,
  actorSystem: ActorSystem,
  materializer: Materializer
 ) extends GridLogging {

  implicit val mat = materializer
  implicit val dispatcher = actorSystem.getDispatcher

  val mergedKinesisSource: Source[TaggedRecord[UpdateMessage], NotUsed] = Source.fromGraph(GraphDSL.create() { implicit graphBuilder =>
    import GraphDSL.Implicits._

    val uiRecordSource = uiSource.map(kinesisRecord =>
      TaggedRecord(kinesisRecord.data.toArray, kinesisRecord.approximateArrivalTimestamp, UiPriority, kinesisRecord.markProcessed))

    val automationRecordSource = automationSource.map(kinesisRecord =>
      TaggedRecord(kinesisRecord.data.toArray, kinesisRecord.approximateArrivalTimestamp, AutomationPriority, kinesisRecord.markProcessed))

    val reingestionRecordSource = reingestionSource.map { case (updateMessage, time) =>
      TaggedRecord(updateMessage, time, ReingestionPriority, () => {})
    }

    // merge together ui and automation kinesis records
    val uiAndAutomationRecordsMerge = graphBuilder.add(MergePreferred[TaggedRecord[Array[Byte]]](1))
    uiRecordSource ~> uiAndAutomationRecordsMerge.preferred
    automationRecordSource  ~> uiAndAutomationRecordsMerge.in(0)

    // parse the kinesis records into thrall update messages (dropping those that fail)
    val uiAndAutomationMessagesSource =
      uiAndAutomationRecordsMerge.out
        .map { taggedRecord =>
          ThrallEventConsumer
            .parseRecord(taggedRecord.payload, taggedRecord.arrivalTimestamp)
            .map(message => taggedRecord.copy(payload = message))
        }
        .collect {
          case Right(record) => record
        }

    // merge in the re-ingestion source (preferring ui/automation)
    val reingestionMerge = graphBuilder.add(MergePreferred[TaggedRecord[UpdateMessage]](1))
    uiAndAutomationMessagesSource ~> reingestionMerge.preferred
    reingestionRecordSource ~> reingestionMerge.in(0)

    SourceShape(reingestionMerge.out)
  })

  def createStream(): Source[(TaggedRecord[UpdateMessage], Stopwatch, Either[Throwable, ThrallMessage]), NotUsed] = {
    mergedKinesisSource.map{ taggedRecord =>
      taggedRecord -> MessageTranslator.translate(taggedRecord.payload)
    }.mapAsync(1) { result =>
      val stopwatch = Stopwatch.start
      result match {
        case (record, Right(updateMessage)) =>
          consumer.processUpdateMessage(updateMessage)
            .recover { case _ => () }
            .map(_ => (record, stopwatch, Right(updateMessage)))
        case (record, Left(whoops)) => {
          logger.warn("Unable to parse message.", whoops)
          Future.successful((record, stopwatch, Left(whoops)))
        }
      }
    }
  }

  def run(): Future[Done] = {
    val stream = this.createStream().runForeach {
      case (taggedRecord, stopwatch, _) =>
        val markers = combineMarkers(taggedRecord, stopwatch.elapsed)
        logger.info(markers, "Record processed")
        taggedRecord.markProcessed()
    }

    stream.onComplete {
      case Failure(exception) => logger.error("stream completed with failure", exception)
      case Success(_) => logger.info("Stream completed with done, probably shutting down")
    }

    stream
  }
}
