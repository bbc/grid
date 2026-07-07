package lib.elasticsearch

import com.gu.mediaservice.lib.elasticsearch.client._
import com.gu.mediaservice.lib.elasticsearch.client.GridEsQueryDsl._

object sorts {

  private val UploadTimeDescending: GridSort = GridFieldSort("uploadTime", GridSortOrder.DESC)

  private val HasDescFieldPrefix = "-(.+)".r

  // extensible list of sort field replacements
  private val SortReplacements = List(
    ("taken", "metadata.dateTaken,-uploadTime")
  )

  def createSort(sortBy: Option[String]): Seq[GridSort] =
    sortBy.fold(Seq(UploadTimeDescending))(parseSortBy)

  def dateAddedToCollectionDescending: Seq[GridSort] =
    Seq(GridFieldSort("collections.actionData.date", GridSortOrder.DESC))

  private def parseSortBy(sortBy: String): Seq[GridSort] = {
    val sortString = SortReplacements.foldLeft(sortBy) { (str, r) =>
      str.replace(r._1, r._2)
    }
    sortString.split(',').toList.map {
      case HasDescFieldPrefix(field) => GridFieldSort(field, GridSortOrder.DESC)
      case field                     => GridFieldSort(field, GridSortOrder.ASC)
    }
  }
}
