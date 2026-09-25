package com.gwgs.akkaagentic.feed.application

import scala.util.{Failure, Success, Try}

import com.gwgs.akkaagentic.feed.domain.DeliveryKey

/** How every delivery in this capability is handled: under a bound the consumer enforces **itself**.
  *
  * Measured, on the real projection path (specs/018 research Q-D):
  *   - a handler that throws is redelivered **without limit** — 0, 277, 789, 1719, 3419, 6985, 13976,
  *     27611 ms and still climbing, roughly doubling;
  *   - while that happens, **every other user's changes wait behind it** — a change for an unrelated user,
  *     written after the failure began, arrived only once the failing one stopped failing;
  *   - a handler that gives up and returns `done()` released the stream **within 1 ms**.
  *
  * So the bound is not a refinement; without it one bad message silently stops the capability for everyone.
  * The SDK offers nothing to count with — `MessageContext` has no attempt number and `ce-id` changes on
  * every redelivery — so attempts are counted per **(consumer, user, state fingerprint)**.
  *
  * Shared on purpose: the production consumer and the always-failing probe both run through here, so the
  * bound the failure test proves is the shipped code path, not a copy of it (capability 15's shape).
  */
object BoundedDelivery:

  enum Outcome:
    /** The work completed; the delivery's attempt count is spent. */
    case Succeeded

    /** It failed with attempts left: the caller should rethrow `cause` so the runtime redelivers. */
    case WillRetry(attempt: Int, cause: Throwable)

    /** It failed on its last permitted attempt: the delivery is recorded as set aside, and the caller
      * should return `done()` so the stream moves on for everyone else. */
    case GaveUp(attempt: Int, cause: Throwable)

  /** Run one attempt of `work` for a delivery, under a bound of `limit` attempts.
    *
    * Only non-fatal failures are caught, so a fatal JVM error is never mistaken for a poison message.
    */
  def run(consumer: String, username: String, fingerprint: String, limit: Int)(work: => Unit): Outcome =
    val key = DeliveryKey(consumer, username, fingerprint)
    Try(work) match
      case Success(_) =>
        ActivityStore.settled(key)
        Outcome.Succeeded
      case Failure(cause) =>
        val attempt = ActivityStore.attempted(key)
        if attempt >= limit then
          ActivityStore.setAside(username, fingerprint, attempt, reason(cause))
          ActivityStore.settled(key)
          Outcome.GaveUp(attempt, cause)
        else Outcome.WillRetry(attempt, cause)

  private def reason(cause: Throwable): String =
    s"${cause.getClass.getSimpleName}: ${Option(cause.getMessage).getOrElse("(no message)")}"
