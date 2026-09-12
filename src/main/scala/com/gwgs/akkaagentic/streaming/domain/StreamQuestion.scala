package com.gwgs.akkaagentic.streaming.domain

/** A question that is known to be present — capability 14's validated input.
  *
  * Parse, don't validate: [[StreamQuestion.validate]] is the only way to get one, so anything holding
  * a `StreamQuestion` has a non-blank, trimmed question and never re-checks or calls `.get`.
  *
  * **No Akka import** (Constitution II, domain independence). The rule is the same non-blank check
  * capability 8 makes, and it is deliberately *not* capability 8's code: reusing
  * `docs.domain.AskQuestion` would couple two capabilities for four lines and put a capability-14 edit
  * one step from capability 8's sources, which FR-009 forbids.
  *
  * **A note for the caller, which is Java by force.** The streaming endpoint must be Java — it holds
  * the `tokenStream` method reference (research Q-B) — so this idiomatic `Option`/`Either` API is
  * consumed from Java through Scala's static forwarders. That is the first time in this project the
  * language-of-consumer guidance (README §8) meets a consumer whose language was chosen *for* it by
  * the SDK rather than by us. The domain keeps its idiomatic shape; how that reads from Java is
  * measured at the call site rather than pre-empted here.
  */
final case class StreamQuestion(question: String)

object StreamQuestion:

  /** The exact text a `400` carries, shared so the endpoint, the contract and the tests cannot
    * drift apart. */
  val BlankMessage: String = "question must not be blank"

  /** `None`, empty, or whitespace-only is a `Left`; anything else is a `Right` carrying the trimmed
    * question. Only the ends are trimmed — inner spacing is content. */
  def validate(question: Option[String]): Either[String, StreamQuestion] =
    question.map(_.trim).filter(_.nonEmpty) match
      case Some(text) => Right(StreamQuestion(text))
      case None       => Left(BlankMessage)
