package com.gu.mediaservice.lib.config

import org.apache.pekko.actor.ActorSystem
import org.mockito.Mockito.when
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Environment}

class CommonConfigTest extends AnyFunSuiteLike with MockitoSugar {

  private val commonConf = mock[CommonConfig]
  when(commonConf.configuration).thenReturn(Configuration(
    "setAsCommaSepString" -> "a, b,c",
    "setAsActualArray" -> Set("a", "b", "c")
  ))

  private val defaultConfiguration = Configuration(
    Configuration.load(Environment.simple()).underlying.withoutPath("domainMetadata.workflowOwnedBooleanFields")
  )

  private def configWith(overrides: Configuration): CommonConfig = new CommonConfig(GridConfigResources(
    overrides.withFallback(defaultConfiguration),
    mock[ActorSystem],
    mock[ApplicationLifecycle]
  )) {}

  test("testGetOptionalStringSet") {
      commonConf.getOptionalStringSet("doesnt.exist") shouldBe None
      commonConf.getOptionalStringSet("setAsCommaSepString") shouldBe Some(Set("a", "b", "c"))
      commonConf.getOptionalStringSet("setAsActualArray") shouldBe Some(Set("a", "b", "c"))
  }

  test("testGetStringSet") {
    commonConf.getStringSet("doesnt.exist") shouldBe Set.empty
    commonConf.getStringSet("setAsCommaSepString") shouldBe Set("a", "b", "c")
    commonConf.getStringSet("setAsActualArray") shouldBe Set("a", "b", "c")
  }

  test("workflow-owned Boolean fields default to empty when absent") {
    configWith(Configuration.empty).workflowOwnedBooleanFields shouldBe Seq.empty
  }

  test("workflow-owned Boolean fields retain configured relative paths") {
    val fields = List("exampleNamespace.first", "otherNamespace.second")
    val overrides = Configuration.from(Map("domainMetadata.workflowOwnedBooleanFields" -> fields))

    configWith(overrides).workflowOwnedBooleanFields shouldBe fields
  }

}
