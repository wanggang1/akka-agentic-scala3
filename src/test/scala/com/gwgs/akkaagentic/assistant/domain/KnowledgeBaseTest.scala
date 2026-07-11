package com.gwgs.akkaagentic.assistant.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pure unit tests for the canned knowledge base. No Akka runtime needed. */
class KnowledgeBaseTest:

  @Test
  def knownTopicReturnsEntry(): Unit =
    val entry = KnowledgeBase.lookup("password-reset")
    assertThat(entry.isDefined).isTrue()
    assertThat(entry.get.topic).isEqualTo("password-reset")
    assertThat(entry.get.summary).contains("Forgot password")

  @Test
  def lookupIsCaseAndWhitespaceInsensitive(): Unit =
    // The model may pass the topic in any casing / with stray spaces; all resolve to the same entry.
    assertThat(KnowledgeBase.lookup("  Password-Reset ")).isEqualTo(KnowledgeBase.lookup("password-reset"))
    assertThat(KnowledgeBase.lookup("BILLING").map(_.topic)).isEqualTo(Some("billing"))

  @Test
  def unknownTopicReturnsNone(): Unit =
    assertThat(KnowledgeBase.lookup("time-travel")).isEqualTo(None)

  @Test
  def nullTopicReturnsNone(): Unit =
    // Null-safe: a null topic from the wire never throws — it is simply a miss.
    assertThat(KnowledgeBase.lookup(null)).isEqualTo(None)

  @Test
  def topicsAreNormalized(): Unit =
    // Scala Set → AssertJ ObjectAssert (no iterable .contains), so compare the whole set.
    assertThat(KnowledgeBase.topics).isEqualTo(Set("password-reset", "billing", "account", "shipping"))
