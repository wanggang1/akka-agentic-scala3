package com.gwgs.akkaagentic.streaming.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

import java.util.concurrent.TimeoutException

/** T005 — the pure failure-to-reason mapping (FR-006), unit-tested with no runtime and no model.
  *
  * Written before [[SseErrorReason]] exists, so it fails to compile first (Constitution III).
  *
  * Two things are pinned: the *kind*-based mapping (timeout vs everything else), and — the point of
  * FR-006 — that no reason string ever leaks an internal detail (a class name, the word "Exception",
  * or the underlying failure's own message).
  */
class SseErrorReasonTest:

  @Test
  def aTimeoutMapsToTheTimeoutReason(): Unit =
    assertThat(SseErrorReason.reasonFor(new TimeoutException("initialTimeout of 60 seconds")))
      .isEqualTo(SseErrorReason.TimedOut)

  @Test
  def anyOtherFailureMapsToTheGenericReason(): Unit =
    assertThat(SseErrorReason.reasonFor(new RuntimeException("boom")))
      .isEqualTo(SseErrorReason.Failed)
    assertThat(SseErrorReason.reasonFor(new IllegalStateException()))
      .isEqualTo(SseErrorReason.Failed)

  /** FR-006: the client-safe reason must not carry the throwable's own message, which can itself
    * contain internal detail (e.g. a model-provider error body). */
  @Test
  def theReasonDoesNotForwardTheUnderlyingMessage(): Unit =
    val leaky = new RuntimeException("io.internal.ProviderClient: secret-host:443 refused")
    val reason = SseErrorReason.reasonFor(leaky)
    assertThat(reason).doesNotContain("ProviderClient")
    assertThat(reason).doesNotContain("secret-host")

  /** FR-006: no reason string names an exception type or uses the word "Exception". */
  @Test
  def noReasonLeaksAClassNameOrTheWordException(): Unit =
    for reason <- List(SseErrorReason.TimedOut, SseErrorReason.Failed) do
      assertThat(reason).doesNotContain("Exception")
      assertThat(reason).doesNotContain(".")
