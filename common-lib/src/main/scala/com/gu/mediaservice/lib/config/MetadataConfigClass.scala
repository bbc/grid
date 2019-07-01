package com.gu.mediaservice.lib.config

import play.api.libs.json._

case class MetadataConfigClass (
  staffIllustrators: List[String],
  creativeCommonsLicense: List[String],
  externalStaffPhotographers: List[Company],
  internalStaffPhotographers: List[Company],
  contractedPhotographers: List[Company],
  contractIllustrators: List[Company])
{
  val staffPhotographers: List[Company] = flattenCompanyList(internalStaffPhotographers ++ externalStaffPhotographers)
  val allPhotographers: List[Company] = flattenCompanyList(internalStaffPhotographers ++ externalStaffPhotographers ++ contractedPhotographers)

  def flattenCompanyList(companies: List[Company]): List[Company] = companies
    .groupBy(_.name)
    .map { case (group, companies) => Company(group, companies.flatMap(company => company.photographers)) }
    .toList
}

case class Company(name: String, photographers: List[String])

object Company {
  implicit val companyClassFormats = Json.format[Company]
}

object MetadataConfigClass {
  implicit val metadataConfigClassFormats = Json.format[MetadataConfigClass]
}

