package lib

import com.gu.mediaservice.lib.aws.S3
import com.gu.mediaservice.lib.auth.Internal
import com.gu.mediaservice.lib.imgproxy.ImgProxyWatermark
import com.gu.mediaservice.lib.logging.MarkerMap
import com.gu.mediaservice.model._
import com.gu.mediaservice.model.usage.{PendingUsageStatus, PrintUsage, Usage}
import lib.elasticsearch.{Fixtures, SourceWrapper}
import org.joda.time.DateTime
import org.joda.time.DateTime.now
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json._

import java.net.URI

class ImageResponseTest extends AnyFunSpec with Matchers with Fixtures with MockitoSugar {

  val mediaApiConfig: MediaApiConfig = buildMediaApiConfig(Map(
    "field.aliases" -> List(
      Map(
        "elasticsearchPath" -> "fileMetadata.xmp.org:ProgrammeMaker",
        "alias" -> "orgProgrammeMaker",
        "label" -> "Organization Programme Maker",
        "displaySearchHint" -> false
      ),
      Map(
        "elasticsearchPath" -> "fileMetadata.xmp.aux:Lens",
        "alias" -> "auxLens",
        "label" -> "Aux Lens",
        "displaySearchHint" -> false
      ),
      Map(
        "elasticsearchPath" -> "fileMetadata.iptc.Caption Writer/Editor",
        "alias" -> "captionWriter",
        "label" -> "Caption Writer / Editor",
        "displaySearchHint" -> true
      ),
      Map(
        "elasticsearchPath" -> "fileMetadata.c2pa.isAvailable",
        "alias" -> "c2paMetadataAvailable",
        "label" -> "C2PA Metadata Available",
        "displaySearchHint" -> true,
        "matchViaExistence" -> true
      )
    )
  ))

  private val imgproxySettings: Map[String, Any] = Map(
    "images.imgproxy.enabled" -> true,
    "images.imgproxy.key" -> "736563726574",
    "images.imgproxy.salt" -> "68656c6c6f",
    "domain.root" -> "example.test"
  )
  private def watermarkSettingsForPath(elasticsearchPath: String): Map[String, Any] = Map(
    "field.aliases" -> List(
      Map(
        "elasticsearchPath" -> elasticsearchPath,
        "alias" -> "privateImage",
        "label" -> "Private image",
        "displaySearchHint" -> false
      )
    ),
    "images.imgproxy.watermark.enabled" -> true,
    "images.imgproxy.watermark.alias" -> "privateImage"
  )
  private val watermarkSettings = watermarkSettingsForPath("metadata.domainMetadata.imagesToolkit.private")
  private val watermarkSegment = "/wm:50:ce:0:0:0.25"
  private val baseImgproxyImage = createImage("imgproxy-watermark-test", NoRights)

  private def imageWithDomainMetadataField(
    specification: String,
    fieldName: String,
    value: Option[JsValue],
    withOptimisedPng: Boolean = false
  ): Image = {
    val domainMetadata = value.fold(Map.empty[String, Map[String, JsValue]]) { fieldValue =>
      Map(specification -> Map(fieldName -> fieldValue))
    }
    val optimisedPng = baseImgproxyImage.source.copy(
      file = new URI("http://file/imgproxy-watermark-test-optimised.png"),
      mimeType = Some(Png)
    )
    baseImgproxyImage.copy(
      metadata = baseImgproxyImage.metadata.copy(domainMetadata = domainMetadata),
      optimisedPng = if (withOptimisedPng) Some(optimisedPng) else None
    )
  }

  private def imageWithBooleanField(value: Option[JsValue], withOptimisedPng: Boolean = false): Image =
    imageWithDomainMetadataField("imagesToolkit", "private", value, withOptimisedPng)

  private def imageResponseFor(config: MediaApiConfig): ImageResponse = {
    val s3Client = new S3(config) {
      override def signUrl(
        bucket: String,
        url: URI,
        image: Image,
        expiration: DateTime,
        imageType: ImageFileType
      ): String = s"https://s3.example.com/$bucket${url.getPath}"
    }
    new ImageResponse(config, s3Client, mock[UsageQuota])
  }

  private def wrapped(image: Image): SourceWrapper[Image] = SourceWrapper(Json.toJson(image), image, "test_index")

  private def signedOptimisedUrls(config: MediaApiConfig, image: Image): Seq[String] = {
    val (json, _, _) = imageResponseFor(config).create(
      id = image.id,
      imageWrapper = wrapped(image),
      withWritePermission = false,
      withDeleteImagePermission = false,
      withDeleteCropsOrUsagePermission = false,
      tier = Internal,
      withSignedOptimisedUrls = true
    )(MarkerMap())
    (json \ "signedOptimisedUrls").as[Seq[JsObject]].map(url => (url \ "url").as[String])
  }

  private def signedPath(url: String, config: MediaApiConfig): String = {
    val urlPath = new URI(url).getPath
    val basePath = Option(new URI(config.imgproxyUri).getPath).getOrElse("").stripSuffix("/")
    val pathAndSignature = urlPath.stripPrefix(basePath).stripPrefix("/")
    val signatureEnd = pathAndSignature.indexOf('/')
    assert(signatureEnd > 0, s"Expected a signed imgproxy path in $url")
    val signature = pathAndSignature.take(signatureEnd)
    val path = pathAndSignature.drop(signatureEnd)
    config.imgproxySigning.map(_.sign(path)) shouldBe Some(signature)
    path
  }

  private def assertWatermarkedAndSigned(url: String, config: MediaApiConfig): Unit =
    signedPath(url, config).contains(watermarkSegment) shouldBe true

  describe("updateUsageRights (legacy category mapping)") {
    it("should map a legacy category onto its composite replacement when that replacement is in config.applicableUsageRights") {
      PayToUse.allCategories.list.toList should contain(Chargeable.category)

      val imageResponse = new ImageResponse(
        mediaApiConfigWithApplicableUsageRights(List("com.gu.mediaservice.model.PayToUse")),
        null, null
      )
      val source = Json.obj("usageRights" -> Json.obj("category" -> Chargeable.category))
      val result = source.transform(imageResponse.updateUsageRights(source)).get

      (result \ "usageRights" \ "category").as[String] shouldBe PayToUse.category
      (result \ "usageRights" \ "legacyCategory").as[String] shouldBe Chargeable.category
    }

    it("should leave a legacy category untouched when its composite replacement is not in config.applicableUsageRights") {
      val imageResponse = new ImageResponse(
        mediaApiConfigWithApplicableUsageRights(List("com.gu.mediaservice.model.Chargeable")),
        null, null
      )
      val source = Json.obj("usageRights" -> Json.obj("category" -> Chargeable.category))
      val result = source.transform(imageResponse.updateUsageRights(source)).get

      (result \ "usageRights" \ "category").as[String] shouldBe Chargeable.category
      (result \ "usageRights" \ "legacyCategory").toOption shouldBe None
    }

    it("should map a legacy category's supplier field onto source when replaced by PRAndThirdParty") {
      PRAndThirdParty.legacyCategories should contain(CommissionedAgency.category)

      val imageResponse = new ImageResponse(
        mediaApiConfigWithApplicableUsageRights(List("com.gu.mediaservice.model.PRAndThirdParty")),
        null, null
      )
      val source = Json.obj("usageRights" -> Json.obj("category" -> CommissionedAgency.category, "supplier" -> "Corbis"))
      val result = source.transform(imageResponse.updateUsageRights(source)).get

      (result \ "usageRights" \ "category").as[String] shouldBe PRAndThirdParty.category
      (result \ "usageRights" \ "legacyCategory").as[String] shouldBe CommissionedAgency.category
      (result \ "usageRights" \ "source").as[String] shouldBe "Corbis"
    }

    it("should map a legacy category's suppliers field onto source when replaced by PRAndThirdParty") {
      PRAndThirdParty.legacyCategories should contain(Composite.category)

      val imageResponse = new ImageResponse(
        mediaApiConfigWithApplicableUsageRights(List("com.gu.mediaservice.model.PRAndThirdParty")),
        null, null
      )
      val source = Json.obj("usageRights" -> Json.obj("category" -> Composite.category, "suppliers" -> "REX/Getty Images"))
      val result = source.transform(imageResponse.updateUsageRights(source)).get

      (result \ "usageRights" \ "category").as[String] shouldBe PRAndThirdParty.category
      (result \ "usageRights" \ "legacyCategory").as[String] shouldBe Composite.category
      (result \ "usageRights" \ "source").as[String] shouldBe "REX/Getty Images"
    }

    it("should pass a legacy category's own source field through untouched when replaced by PRAndThirdParty") {
      PRAndThirdParty.legacyCategories should contain(Screengrab.category)

      val imageResponse = new ImageResponse(
        mediaApiConfigWithApplicableUsageRights(List("com.gu.mediaservice.model.PRAndThirdParty")),
        null, null
      )
      val source = Json.obj("usageRights" -> Json.obj("category" -> Screengrab.category, "source" -> "BBC News"))
      val result = source.transform(imageResponse.updateUsageRights(source)).get

      (result \ "usageRights" \ "category").as[String] shouldBe PRAndThirdParty.category
      (result \ "usageRights" \ "legacyCategory").as[String] shouldBe Screengrab.category
      (result \ "usageRights" \ "source").as[String] shouldBe "BBC News"
    }
  }

  it("should replace \\r linebreaks with \\n") {
    val text = "Here is some text\rthat spans across\rmultiple lines\r"
    val normalisedText = ImageResponse.normaliseNewlineChars(text)
    normalisedText shouldBe "Here is some text\nthat spans across\nmultiple lines\n"
  }

  it("should replace \\r\\n linebreaks with \\n") {
    val text = "Here is some text\r\nthat spans across\r\nmultiple lines\r\n"
    val normalisedText = ImageResponse.normaliseNewlineChars(text)
    normalisedText shouldBe "Here is some text\nthat spans across\nmultiple lines\n"
  }

  it("not cause a stack overflow when many consecutive newline characters are present") {
    val text = "\n\r\n\n\n\r\r\r\n" * 10000
    val normalisedText = ImageResponse.normaliseNewlineChars(text)
    normalisedText shouldBe "\n"
  }

  it("should not touch \\n linebreaks") {
    val text = "Here is some text\nthat spans across\nmultiple lines\n"
    val normalisedText = ImageResponse.normaliseNewlineChars(text)
    normalisedText shouldBe "Here is some text\nthat spans across\nmultiple lines\n"
  }

  it("should indicate if image can be deleted" +
    "(it can be deleted if there is no exports or usages)") {

    import TestUtils._

    val testCrop = Crop(Some("crop-id"), None, None, CropSpec("test-uri", Bounds(0, 0, 0, 0), None, rotation = None), None, Nil)
    val testUsage = Usage(id = "usage-id", references = Nil, platform = PrintUsage, media = "test", status = PendingUsageStatus, dateAdded = None, dateRemoved = None, now())

    val imgWithNoExportsAndUsages = img
    import ImageResponse.canImgBeDeleted
    canImgBeDeleted(imgWithNoExportsAndUsages) shouldEqual true
    val imgWithExportsAndUsages = img.copy(exports = List(testCrop)).copy(usages = List(testUsage))
    canImgBeDeleted(imgWithExportsAndUsages) shouldEqual false
    val imgWithOnlyUsages = img.copy(usages = List(testUsage))
    canImgBeDeleted(imgWithOnlyUsages) shouldEqual false
    val imgWithOnlyExports = img.copy(exports = List(testCrop))
    canImgBeDeleted(imgWithOnlyExports) shouldEqual false
  }

  it("should extract set of configured alias fields from sourcewrapper if fields exist in image") {
    val image = createImage(
      id = "test-image-with-filemetadata",
      agency,
      fileMetadata = Some(FileMetadata(
        iptc = Map(
          "Caption/Abstract" -> "the description",
          "Caption Writer/Editor" -> "the editor"
        ),
        exif = Map(
          "Copyright" -> "the copyright",
          "Artist" -> "the artist"
        ),
        xmp = Map(
          "foo" -> JsString("bar"),
          "toolong" -> JsString(stringLongerThan(100000)),
          "org:ProgrammeMaker" -> JsString("xmp programme maker"),
          "aux:Lens" -> JsString("xmp aux lens")
        )))
    )
    val json = Json.toJson(image)
    val sourceWrapper = SourceWrapper[Image](json, image, fromIndex="test_index")

    val extractedFields = ImageResponse.extractAliasFieldValues(mediaApiConfig, sourceWrapper)

    extractedFields.nonEmpty shouldEqual true
    // 3 regular fields plus c2paMetadataAvailable, which is always present for matchViaExistence
    // fields (as an explicit `false` here, since this image has no c2pa data - see below)
    extractedFields should have length 4

    extractedFields.contains("orgProgrammeMaker" -> JsString("xmp programme maker")) shouldEqual true
    extractedFields.contains("auxLens" -> JsString("xmp aux lens")) shouldEqual true
    extractedFields.contains("captionWriter" -> JsString("the editor")) shouldEqual true
    extractedFields.contains("c2paMetadataAvailable" -> JsBoolean(false)) shouldEqual true
  }

  it("should return only the matchViaExistence alias fields (as false) if no other configured alias fields exist in image") {
    val image = createImage(
      id = "test-image-with-no-filemetadata",
      agency,
      fileMetadata = Some(FileMetadata())
    )
    val json = Json.toJson(image)
    val sourceWrapper = SourceWrapper[Image](json, image, fromIndex="test_index")

    val extractedFields = ImageResponse.extractAliasFieldValues(mediaApiConfig, sourceWrapper)

    extractedFields shouldEqual Seq("c2paMetadataAvailable" -> JsBoolean(false))
  }

  it("should extract a matchViaExistence alias field as true when the underlying field is present") {
    val image = createImage(
      id = "test-image-with-c2pa",
      agency,
      fileMetadata = Some(FileMetadata(c2pa = FileMetadata.C2paAvailable))
    )
    val json = Json.toJson(image)
    val sourceWrapper = SourceWrapper[Image](json, image, fromIndex="test_index")

    val extractedFields = ImageResponse.extractAliasFieldValues(mediaApiConfig, sourceWrapper)

    extractedFields.contains("c2paMetadataAvailable" -> JsBoolean(true)) shouldEqual true
  }

  it("should extract a matchViaExistence alias field as false, rather than omitting it, when the underlying field is absent") {
    val image = createImage(
      id = "test-image-without-c2pa",
      agency,
      fileMetadata = Some(FileMetadata(c2pa = FileMetadata.NoC2PA))
    )
    val json = Json.toJson(image)
    val sourceWrapper = SourceWrapper[Image](json, image, fromIndex="test_index")

    val extractedFields = ImageResponse.extractAliasFieldValues(mediaApiConfig, sourceWrapper)

    extractedFields.contains("c2paMetadataAvailable" -> JsBoolean(false)) shouldEqual true
  }

  describe("imgproxy watermarking") {
    it("uses default appearance settings unless overridden") {
      buildMediaApiConfig(imgproxySettings).imgproxyWatermark shouldBe ImgProxyWatermark(50, "ce", 0.25)
      buildMediaApiConfig(imgproxySettings ++ Map(
        "images.imgproxy.watermark.opacity" -> 30,
        "images.imgproxy.watermark.position" -> "soea",
        "images.imgproxy.watermark.scale" -> 0.5
      )).imgproxyWatermark shouldBe ImgProxyWatermark(30, "soea", 0.5)
    }

    it("requires signing when watermarking is enabled") {
      intercept[IllegalArgumentException] {
        buildMediaApiConfig(watermarkSettings + ("domain.root" -> "example.test"))
      }
    }

    it("uses the path of the field alias named by images.imgproxy.watermark.alias") {
      val config = buildMediaApiConfig(
        imgproxySettings ++ watermarkSettingsForPath("metadata.domainMetadata.otherSpec.otherField")
      )
      val image = imageWithDomainMetadataField("otherSpec", "otherField", Some(JsBoolean(true)))

      signedOptimisedUrls(config, image).foreach(assertWatermarkedAndSigned(_, config))
    }

    it("includes the watermark in signed source, optimised-PNG and resized preview URLs") {
      val config = buildMediaApiConfig(imgproxySettings ++ watermarkSettings)
      val imageResponse = imageResponseFor(config)
      val sourceImage = imageWithBooleanField(Some(JsBoolean(true)))
      val optimisedPngImage = imageWithBooleanField(Some(JsBoolean(true)), withOptimisedPng = true)

      signedOptimisedUrls(config, sourceImage).foreach(assertWatermarkedAndSigned(_, config))
      signedOptimisedUrls(config, optimisedPngImage).foreach(assertWatermarkedAndSigned(_, config))

      Seq(Source, OptimisedPng).foreach { assetType =>
        val url = imageResponse.imgproxyResizedUrl(optimisedPngImage, assetType, 640, 480, None)
        assertWatermarkedAndSigned(url, config)
      }
    }

    it("leaves URLs unchanged when watermarking is disabled or the alias value is not Boolean true") {
      val configured = buildMediaApiConfig(imgproxySettings ++ watermarkSettings)
      val unconfigured = buildMediaApiConfig(
        imgproxySettings ++ watermarkSettings + ("images.imgproxy.watermark.enabled" -> false)
      )
      val configuredResponse = imageResponseFor(configured)
      val unconfiguredResponse = imageResponseFor(unconfigured)
      val imagesWithoutTrueFieldValue = Seq(
        imageWithBooleanField(Some(JsBoolean(false))),
        imageWithBooleanField(None),
        imageWithBooleanField(Some(JsString("true")))
      )

      imagesWithoutTrueFieldValue.foreach { image =>
        val actualUrls = signedOptimisedUrls(configured, image)
        actualUrls shouldBe signedOptimisedUrls(unconfigured, image)
        actualUrls.foreach(url => signedPath(url, configured).contains("/wm:") shouldBe false)

        Seq(Source, OptimisedPng).foreach { assetType =>
          configuredResponse.imgproxyResizedUrl(image, assetType, 640, 480, None) shouldBe
            unconfiguredResponse.imgproxyResizedUrl(image, assetType, 640, 480, None)
        }
      }

      Seq(
        imageWithBooleanField(Some(JsBoolean(true))),
        imageWithBooleanField(Some(JsBoolean(true)), withOptimisedPng = true)
      ).foreach { image =>
        val urls = signedOptimisedUrls(unconfigured, image)
        val imageWithFalseField = imageWithBooleanField(Some(JsBoolean(false)), image.optimisedPng.isDefined)
        urls shouldBe signedOptimisedUrls(unconfigured, imageWithFalseField)
        urls.foreach(url => signedPath(url, unconfigured).contains("/wm:") shouldBe false)
        Seq(Source, OptimisedPng).foreach { assetType =>
          val url = unconfiguredResponse.imgproxyResizedUrl(image, assetType, 640, 480, None)
          url shouldBe unconfiguredResponse.imgproxyResizedUrl(imageWithFalseField, assetType, 640, 480, None)
          signedPath(url, unconfigured).contains("/wm:") shouldBe false
        }
      }
    }
  }
}
