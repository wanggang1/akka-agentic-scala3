package com.gwgs.akkaagentic.streaming.domain

import java.util.concurrent.TimeoutException

/** Capability 20: turn a stream failure into a stable, client-safe reason string for an
  * `event: error` SSE frame (FR-006).
  *
  * **No Akka import** (Constitution II, domain independence): a pure `Throwable => String`, so it is
  * unit-testable with no runtime. The endpoint — Java by force of the `tokenStream` method-reference
  * wall — calls this through Scala's static forwarder, inside the `.recover` that converts a failure
  * into a final `SseChatEvent.ErrorEvent` element *before* the SDK's SSE helper can silently swallow it
  * (research Q-C).
  *
  * **The reasons never leak internals.** No stack trace, no exception class name, no message from the
  * underlying failure reaches the client — a model-provider error message can itself contain internal
  * detail, so even `ex.getMessage` is deliberately not forwarded. The caller gets a fixed phrase keyed
  * only on the *kind* of failure.
  *
  * Only two failure kinds are distinguished, because only two are distinguishable at this layer: the
  * endpoint's `initialTimeout` and `idleTimeout` guards both raise [[java.util.concurrent.TimeoutException]].
  * They are told apart by whether any fragment was produced first, which the endpoint knows and the
  * `Throwable` does not — so this function maps every `TimeoutException` to one timeout phrase and the
  * endpoint may choose the more specific one when it has that context. Everything else is a single
  * generic phrase.
  */
object SseErrorReason:

  /** No first token arrived, or generation stalled: the guards' `TimeoutException`. */
  val TimedOut: String = "the response timed out"

  /** Any other model or runtime failure in the token stream. */
  val Failed: String = "the request failed"

  /** Total, pure mapping from a stream failure to a client-safe reason. */
  def reasonFor(t: Throwable): String =
    t match
      case _: TimeoutException => TimedOut
      case _                   => Failed
