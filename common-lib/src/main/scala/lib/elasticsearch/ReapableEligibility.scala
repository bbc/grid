package lib.elasticsearch

import com.sksamuel.elastic4s.ElasticApi.matchNoneQuery
import com.sksamuel.elastic4s.ElasticDsl.matchAllQuery
import com.sksamuel.elastic4s.requests.searches.queries.Query
import org.joda.time.DateTime

trait ReapableEligibility {

  val persistedRootCollections: List[String] // typically from config
  val maybePersistenceIdentifier: Option[String] // typically from config

  private def moreThanSomeDaysOld(days: Int) =
    filters.date("uploadTime", None, Some(DateTime.now().minusDays(days))).getOrElse(matchAllQuery())
  
  def query: Query = filters.and(
    moreThanSomeDaysOld(50),
    PersistedQueries.isSoftDeleted,
    PersistedQueries.isBBCAgency,
  )
}
