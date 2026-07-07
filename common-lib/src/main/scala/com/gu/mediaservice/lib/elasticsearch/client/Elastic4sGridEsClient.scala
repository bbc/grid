package com.gu.mediaservice.lib.elasticsearch.client

import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s._
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.requests.bulk.{BulkRequest, BulkResponseItem}
import com.sksamuel.elastic4s.requests.common.HealthStatus
import com.sksamuel.elastic4s.requests.indexes.admin.IndexExistsResponse
import com.sksamuel.elastic4s.requests.script.{Script => Es4sScript}
import com.sksamuel.elastic4s.requests.searches.{SearchRequest, SearchResponse, SearchHit => Es4sSearchHit}
import com.sksamuel.elastic4s.requests.searches.aggs.responses.bucket.{DateHistogram, Terms}
import com.sksamuel.elastic4s.requests.searches.aggs.responses.metrics.TopHits
import com.sksamuel.elastic4s.requests.searches.aggs.responses.Aggregations
import com.sksamuel.elastic4s.requests.searches.queries.{Query => Es4sQuery}
import com.sksamuel.elastic4s.requests.searches.sort.{FieldSort, SortOrder => Es4sSortOrder}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * elastic4s-backed implementation of [[GridEsClient]].
 *
 * This is the only file in the project that is allowed to import
 * com.sksamuel.elastic4s types.  All conversions between internal types and
 * elastic4s types live here.
 */
class Elastic4sGridEsClient(url: String) extends GridEsClient {

  private val esClient: ElasticClient = {
    val jc = JavaClient(ElasticProperties(url))
    ElasticClient(jc)
  }

  def close(): Unit = esClient.close()

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private def handleResponse[A, B](
    response: Response[A],
    notFoundSuccessful: Boolean = false
  )(f: A => B): B = {
    if (response.isSuccess) {
      f(response.result)
    } else {
      response.status match {
        case 404 if notFoundSuccessful => f(response.result)
        case 404                       => throw GridEsNotFoundException
        case _                         => throw toGridException(response.error)
      }
    }
  }

  private def toGridError(e: ElasticError): GridEsError =
    GridEsError(
      `type`  = e.`type`,
      reason  = e.reason,
      causedBy = e.causedBy.map(c => GridEsCausedBy(
        `type`      = c.`type`,
        reason      = c.reason,
        scriptStack = c.scriptStack,
        script      = c.other("script").map(_.toString)
      ))
    )

  private def toGridException(e: ElasticError): Throwable =
    new RuntimeException(s"[${e.`type`}] ${e.reason}")

  // ─── Query converter ──────────────────────────────────────────────────────

  private def toEs4sQuery(q: GridEsQuery): Es4sQuery = Elastic4sQueryConverter.convert(q)

  // ─── Script converter ─────────────────────────────────────────────────────

  private def toEs4sScript(s: GridEsScript): Es4sScript =
    Es4sScript(script = s.source).lang(s.lang).params(s.params)

  // ─── Sort converter ───────────────────────────────────────────────────────

  private def toEs4sSort(s: GridSort): FieldSort = s match {
    case GridFieldSort(field, GridSortOrder.ASC)  => fieldSort(field).order(Es4sSortOrder.ASC)
    case GridFieldSort(field, GridSortOrder.DESC) => fieldSort(field).order(Es4sSortOrder.DESC)
  }

  // ─── Aggregation request converter ───────────────────────────────────────

  private def toEs4sAgg(a: GridEsAggregation): com.sksamuel.elastic4s.requests.searches.aggs.Aggregation = a match {
    case a: GridTermsAggregation =>
      val ta = termsAgg(a.name, a.field).size(a.size)
      if (a.subAggregations.nonEmpty) ta.subAggregations(a.subAggregations.map(toEs4sAgg))
      else ta

    case a: GridTopHitsAggregation =>
      topHitsAgg(a.name).size(a.size).fetchSource(a.fetchSource)

    case a: GridDateHistogramAggregation =>
      import com.sksamuel.elastic4s.requests.searches.DateHistogramInterval
      var da = dateHistogramAgg(a.name, a.field)
      a.calendarInterval.foreach(i => da = da.calendarInterval(DateHistogramInterval.fromString(i)))
      a.minDocCount.foreach(n => da = da.minDocCount(n))
      da

    case a: GridFilterAggregation =>
      val fa = filterAgg(a.name, toEs4sQuery(a.query))
      if (a.subAggregations.nonEmpty) fa.subAggregations(a.subAggregations.map(toEs4sAgg))
      else fa
  }

  // ─── Search request converter ─────────────────────────────────────────────

  private def toEs4sSearch(r: GridSearchRequest): SearchRequest = {
    var sr = ElasticDsl.search(r.index)
    sr = sr.query(toEs4sQuery(r.query))
    r.from.foreach(n => sr = sr.from(n))
    r.size.foreach(n => sr = sr.size(n))
    if (r.sorts.nonEmpty) sr = sr.sortBy(r.sorts.map(toEs4sSort))
    if (r.aggregations.nonEmpty) sr = sr.aggregations(r.aggregations.map(toEs4sAgg))
    r.trackTotalHits.foreach(b => sr = sr.trackTotalHits(b))
    if (r.storedFields.nonEmpty) sr = sr.storedFields(r.storedFields)
    if (r.scriptFields.nonEmpty) sr = sr.scriptfields(r.scriptFields.map(sf =>
      com.sksamuel.elastic4s.requests.script.ScriptField(sf.name, toEs4sScript(sf.script))
    ))
    if (r.runtimeMappings.nonEmpty) sr = sr.runtimeMappings(r.runtimeMappings.map(rm =>
      com.sksamuel.elastic4s.requests.searches.RuntimeMapping(
        field = rm.field,
        `type` = rm.`type`,
        scriptSource = rm.scriptSource
      )
    ))
    if (!r.fetchSource) sr = sr.fetchSource(false)
    if (r.version) sr = sr.version(true)
    r.scroll.foreach(d => sr = sr.scroll(s"${d.toSeconds}s"))
    r.timeout.foreach(d => sr = sr.timeout(d))
    // KNN top-level
    r.knn.foreach { k =>
      import com.sksamuel.elastic4s.requests.searches.knn.Knn
      val knn = Knn(k.field)
        .queryVector(k.queryVector)
        .k(k.k)
        .numCandidates(k.numCandidates)
      val knnWithFilter = k.filter.fold(knn)(f => knn.filter(toEs4sQuery(f)))
      val knnWithBoost  = k.boost.fold(knnWithFilter)(b => knnWithFilter.boost(b.toFloat))
      sr = sr.knn(knnWithBoost)
    }
    sr
  }

  // ─── Response converters ──────────────────────────────────────────────────

  private def fromEs4sHit(h: Es4sSearchHit): GridSearchHit =
    GridSearchHit(
      id      = h.id,
      index   = h.index,
      source  = if (h.sourceAsString.isEmpty) None else Some(h.sourceAsString),
      version = if (h.version > 0) Some(h.version) else None,
      fields  = Option(h.fields).map(_.toMap.asInstanceOf[Map[String, Any]]).getOrElse(Map.empty),
      score   = h.score
    )

  private def fromEs4sSearch(r: SearchResponse): GridSearchResponse =
    GridSearchResponse(
      hits = GridSearchHits(
        total = GridTotalHits(r.hits.total.value),
        hits  = r.hits.hits.toSeq.map(fromEs4sHit)
      ),
      scrollId     = r.scrollId,
      aggregations = new Elastic4sAggregations(r.aggregations),
      took         = r.took,
      timedOut     = r.isTimedOut
    )

  // ─── Aggregation response converter ──────────────────────────────────────

  private class Elastic4sAggregations(backing: Aggregations) extends GridAggregations {

    def terms(name: String): GridTermsAggResult = {
      val t = backing.result[Terms](name)
      GridTermsAggResult(
        buckets       = t.buckets.map(b => GridTermsBucket(
          key      = b.key,
          docCount = b.docCount,
          subAggs  = new Elastic4sSubAggregations(b)
        )),
        otherDocCount = t.otherDocCount
      )
    }

    def topHits(name: String): GridTopHitsAggResult = {
      val th = backing.result[TopHits](name)
      GridTopHitsAggResult(th.hits.map(h => GridAggregationHit(id = h.id)))
    }

    def dateHistogram(name: String): GridDateHistogramAggResult = {
      val dh = backing.result[DateHistogram](name)
      GridDateHistogramAggResult(dh.buckets.map(b =>
        GridDateHistogramBucket(key = b.date, docCount = b.docCount)
      ))
    }

    def filter(name: String): GridFilterAggResult = {
      val f = backing.filter(name)
      new GridFilterAggResult {
        override def docCount: Long                                      = f.docCount
        override def terms(n: String): GridTermsAggResult               = new Elastic4sSubAggregations(f).terms(n)
        override def topHits(n: String): GridTopHitsAggResult           = new Elastic4sSubAggregations(f).topHits(n)
        override def dateHistogram(n: String): GridDateHistogramAggResult = new Elastic4sSubAggregations(f).dateHistogram(n)
      }
    }
  }

  private class Elastic4sSubAggregations(
    bucket: com.sksamuel.elastic4s.requests.searches.aggs.responses.HasAggregations
  ) extends GridAggregations {

    def terms(name: String): GridTermsAggResult = {
      val t = bucket.result[Terms](name)
      GridTermsAggResult(
        buckets       = t.buckets.map(b => GridTermsBucket(
          key      = b.key,
          docCount = b.docCount,
          subAggs  = new Elastic4sSubAggregations(b)
        )),
        otherDocCount = t.otherDocCount
      )
    }

    def topHits(name: String): GridTopHitsAggResult = {
      val th = bucket.result[TopHits](name)
      GridTopHitsAggResult(th.hits.map(h => GridAggregationHit(id = h.id)))
    }

    def dateHistogram(name: String): GridDateHistogramAggResult = {
      val dh = bucket.result[DateHistogram](name)
      GridDateHistogramAggResult(dh.buckets.map(b =>
        GridDateHistogramBucket(key = b.date, docCount = b.docCount)
      ))
    }

    def filter(name: String): GridFilterAggResult = {
      val f = bucket.filter(name)
      new GridFilterAggResult {
        override def docCount: Long = f.docCount
        override def terms(n: String): GridTermsAggResult = new Elastic4sSubAggregations(f).terms(n)
        override def topHits(n: String): GridTopHitsAggResult = new Elastic4sSubAggregations(f).topHits(n)
        override def dateHistogram(n: String): GridDateHistogramAggResult = new Elastic4sSubAggregations(f).dateHistogram(n)
      }
    }
  }

  // ─── GridEsClient implementation ─────────────────────────────────────────

  override def getDocument(index: String, id: String, sourceIncludes: Seq[String] = Nil)
                          (implicit ec: ExecutionContext): Future[GridGetResponse] = {
    val req = if (sourceIncludes.nonEmpty)
      get(index, id).fetchSourceInclude(sourceIncludes: _*)
    else
      get(index, id)
    esClient.execute(req).map { r =>
      handleResponse(r) { g =>
        GridGetResponse(
          id           = g.id,
          index        = g.index,
          found        = g.found,
          source       = if (g.found) Some(g.sourceAsString) else None,
          version      = g.version,
          storedFields = Option(g.fields).map(_.toMap.view.mapValues(_.asInstanceOf[Any]).toMap).getOrElse(Map.empty)
        )
      }
    }
  }

  override def indexDocument(index: String, id: String, source: String)
                             (implicit ec: ExecutionContext): Future[GridIndexResponse] =
    esClient.execute(indexInto(index).id(id).source(source)).map { r =>
      handleResponse(r)(res => GridIndexResponse(res.index))
    }

  override def updateDocumentWithScript(index: String, id: String, script: GridEsScript)
                                        (implicit ec: ExecutionContext): Future[GridUpdateResponse] =
    esClient.execute(updateById(index, id).script(toEs4sScript(script))).map { r =>
      handleResponse(r)(_ => GridUpdateResponse())
    }

  override def upsertDocumentWithScript(index: String, id: String, script: GridEsScript, upsertDoc: String)
                                        (implicit ec: ExecutionContext): Future[GridUpdateResponse] =
    esClient.execute(updateById(index, id).script(toEs4sScript(script)).upsert(upsertDoc)).map { r =>
      handleResponse(r)(_ => GridUpdateResponse())
    }

  override def updateDocumentWithDoc(index: String, id: String, doc: String)
                                     (implicit ec: ExecutionContext): Future[GridUpdateResponse] =
    esClient.execute(updateById(index, id).doc(doc)).map { r =>
      handleResponse(r)(_ => GridUpdateResponse())
    }

  override def deleteDocument(index: String, id: String)
                              (implicit ec: ExecutionContext): Future[GridDeleteResponse] =
    esClient.execute(deleteById(index, id)).map { r =>
      handleResponse(r)(_ => GridDeleteResponse())
    }

  override def search(request: GridSearchRequest)
                     (implicit ec: ExecutionContext): Future[GridSearchResponse] =
    esClient.execute(toEs4sSearch(request)).map { r =>
      handleResponse(r)(fromEs4sSearch)
    }

  override def count(index: String, query: GridEsQuery)
                    (implicit ec: ExecutionContext): Future[Long] =
    esClient.execute(ElasticDsl.count(index).query(toEs4sQuery(query))).map { r =>
      handleResponse(r)(_.count)
    }

  override def catCount(index: String)(implicit ec: ExecutionContext): Future[Long] =
    esClient.execute(ElasticDsl.catCount(index)).map { r =>
      handleResponse(r)(_.count)
    }

  override def startScroll(request: GridSearchRequest)
                           (implicit ec: ExecutionContext): Future[GridSearchResponse] =
    // scroll is set on the request via request.scroll(duration)
    esClient.execute(toEs4sSearch(request)).map { r =>
      handleResponse(r)(fromEs4sSearch)
    }

  override def continueScroll(scrollId: String, keepAlive: FiniteDuration)
                              (implicit ec: ExecutionContext): Future[GridSearchResponse] =
    esClient.execute(searchScroll(scrollId).keepAlive(s"${keepAlive.toSeconds}s")).map { r =>
      handleResponse(r)(fromEs4sSearch)
    }

  override def clearScroll(scrollId: String)(implicit ec: ExecutionContext): Future[Unit] =
    esClient.execute(ElasticDsl.clearScroll(scrollId)).map(_ => ())

  override def bulk(operations: Seq[GridBulkOperation])
                   (implicit ec: ExecutionContext): Future[GridBulkResponse] = {
    val esOps = operations.map {
      case GridBulkUpdateByScript(index, id, script) =>
        updateById(index, id).script(toEs4sScript(script))
      case GridBulkDelete(index, id) =>
        deleteById(index, id)
    }
    esClient.execute(ElasticDsl.bulk(esOps)).map { r =>
      handleResponse(r) { res =>
        GridBulkResponse(res.items.map((item: BulkResponseItem) =>
          GridBulkItem(
            id    = item.id,
            error = item.error.map(e => GridBulkItemError(e.reason))
          )
        ))
      }
    }
  }

  override def indexExists(index: String)(implicit ec: ExecutionContext): Future[Boolean] =
    esClient.execute(ElasticDsl.indexExists(index)).map { r =>
      if (r.isError) false else r.result.exists
    }

  override def createIndex(
    index: String,
    bodyJson: String,
    shards: Int,
    replicas: Int,
    additionalSettings: Map[String, Any] = Map.empty
  )(implicit ec: ExecutionContext): Future[Either[GridEsError, Boolean]] = {
    val settings: Map[String, Any] = additionalSettings ++ Map(
      "number_of_shards"   -> shards,
      "number_of_replicas" -> replicas
    )
    esClient.execute(
      ElasticDsl.createIndex(index)
        .source(bodyJson)
        .settings(settings)
    ).map { r =>
      if (r.isError) Left(toGridError(r.error))
      else Right(r.result.acknowledged)
    }
  }

  override def indexStats(index: String)(implicit ec: ExecutionContext): Future[GridIndexStatsResponse] =
    esClient.execute(ElasticDsl.indexStats(index)).map { r =>
      handleResponse(r) { stats =>
        GridIndexStatsResponse(
          indices = stats.indices.view.mapValues { idxStats =>
            GridIndexStats(
              total = GridIndexTotals(GridIndexDocCount(idxStats.total.docs.count))
            )
          }.toMap
        )
      }
    }

  override def getAliases(alias: String)(implicit ec: ExecutionContext): Future[Seq[GridIndexWithAliases]] =
    esClient.execute(ElasticDsl.getAliases(alias, Nil)).map { r =>
      if (r.isError) Seq.empty
      else r.result.mappings.map { case (index, aliases) =>
        GridIndexWithAliases(
          name    = index.name,
          aliases = aliases.map(_.name).toSeq
        )
      }.toSeq
    }

  override def modifyAliases(actions: Seq[GridAliasAction])
                             (implicit ec: ExecutionContext): Future[Either[GridEsError, Boolean]] = {
    val esActions = actions.map {
      case GridAddAlias(idx, alias)    => addAlias(alias, idx)
      case GridRemoveAlias(idx, alias) => removeAlias(alias, idx)
    }
    esClient.execute(aliases(esActions)).map { r =>
      if (r.isError) Left(toGridError(r.error))
      else Right(r.result.success)
    }
  }

  override def waitForGreenHealth(timeout: String)(implicit ec: ExecutionContext): Future[Boolean] =
    esClient.execute(
      clusterHealth().waitForStatus(HealthStatus.Green).timeout(timeout)
    ).map { r =>
      r.isSuccess
    }
}

