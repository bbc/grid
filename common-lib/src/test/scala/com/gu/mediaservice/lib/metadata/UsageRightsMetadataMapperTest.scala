package com.gu.mediaservice.lib.metadata

import com.gu.mediaservice.model._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class UsageRightsMetadataMapperTest extends AnyFunSpec with Matchers {

  import com.gu.mediaservice.lib.metadata.UsageRightsMetadataMapper.usageRightsToMetadata

  describe("UsageRights => ImageMetadata") {
    val metadataWithoutCopyright = ImageMetadata(copyright = None)
    val metadataWithCopyright = ImageMetadata(copyright = Some("BBC"))

    it("should convert StaffPhotographers adding copyright when original metadata doesn't have it") {
      val ur = StaffPhotographer("Alicia Canter", "The Guardian")
      usageRightsToMetadata(ur, metadataWithoutCopyright) should be
      Some(ImageMetadata(credit = Some("The Guardian"), byline = Some("Alicia Canter"), copyright = Some("The Guardian"), imageType = Some("Photograph")))
    }

    it ("should convert StaffPhotographers changing copyright when original copyright is a publication") {
      val ur = StaffPhotographer("Alicia Canter", "The Guardian")
      usageRightsToMetadata(ur, metadataWithCopyright, Set("The Observer", "BBC")) should be
        Some(ImageMetadata(credit = Some("The Guardian"), byline = Some("Alicia Canter"), copyright = Some("The Guardian"), imageType = Some("Photograph")))
    }

    it ("should convert StaffPhotographers keeping original copyright when original copyright is not a publication") {
      val ur = StaffPhotographer("Alicia Canter", "The Guardian")
      usageRightsToMetadata(ur, metadataWithCopyright, Set("The Observer", "BBC Studio")) should be
      Some(ImageMetadata(credit = Some("The Guardian"), byline = Some("Alicia Canter"), copyright = Some("BBC"), imageType = Some("Photograph")))
    }

    it ("should convert ContractPhotographers") {
      val ur = ContractPhotographer("Andy Hall", Some("The Observer"), None)
      usageRightsToMetadata(ur, metadataWithCopyright) should be
        Some(ImageMetadata(credit = Some("The Observer"), byline = Some("Andy Hall"), imageType = Some("Photograph")))
    }

    it ("should convert CommissionedPhotographers") {
      val ur = CommissionedPhotographer("Mr. Photo", Some("Weekend Magazine"))
      usageRightsToMetadata(ur, metadataWithoutCopyright) should be
        Some(ImageMetadata(credit = Some("Weekend Magazine"), byline = Some("Mr. Photo"), imageType = Some("Photograph")))
    }

    it ("should convert ContractIllustrators") {
      val ur = ContractIllustrator("First Dog on the Moon Institute")
      usageRightsToMetadata(ur, metadataWithoutCopyright) should be
        Some(ImageMetadata(credit = Some("First Dog on the Moon Institute"), imageType = Some("Illustration")))
    }

    it ("should convert CommissionedIllustrators") {
      val ur = CommissionedIllustrator("Roger Rabbit")
      usageRightsToMetadata(ur, metadataWithoutCopyright) should be (
        Some(ImageMetadata(credit = Some("Roger Rabit"), imageType = Some("Illustration"))))

    }

    it ("should convert Composites") {
      val ur = Composite("REX/Getty Images")
      usageRightsToMetadata(ur, metadataWithoutCopyright) should be
        Some(ImageMetadata(credit = Some("REX/Getty Images"), imageType = Some("Composite")))
    }

    it ("should convert Screengrabs") {
      val ur = Screengrab(Some("BBC News"))
      usageRightsToMetadata(ur, metadataWithoutCopyright) should be
        Some(ImageMetadata(credit = Some("BBC News")))
    }

    it ("should not convert Agencies") {
      val ur = Agency("Rex Features")
      usageRightsToMetadata(ur, metadataWithoutCopyright) should be(None)
    }

  }
}
