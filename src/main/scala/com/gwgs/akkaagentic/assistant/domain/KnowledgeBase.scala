package com.gwgs.akkaagentic.assistant.domain

/** A canned knowledge-base entry the help-desk agent may consult. */
final case class KnowledgeBaseEntry(topic: String, summary: String)

/** A tiny, in-memory knowledge base the agent consults via its `@FunctionTool`.
  *
  * Pure domain — no Akka dependencies. The point is to demonstrate a model-invoked tool inside the
  * agent's iteration loop, not to build a real retrieval system (spec A-007). Lookup is
  * case-insensitive and total: an unknown topic yields `None` (never throws), so the tool can turn a
  * miss into a clear "no entry" reply rather than an error.
  */
object KnowledgeBase:

  /** The known topics, keyed by their normalized (trimmed, lower-cased) topic id. */
  private val entries: Map[String, KnowledgeBaseEntry] = Seq(
    KnowledgeBaseEntry(
      "password-reset",
      "Reset your password from the sign-in page via \"Forgot password\"; a reset link is emailed " +
        "and expires in 30 minutes."
    ),
    KnowledgeBaseEntry(
      "billing",
      "Invoices are issued monthly; charges can be disputed within 60 days from the Billing page."
    ),
    KnowledgeBaseEntry(
      "account",
      "Manage profile, email, and security settings under Account; account deletion is permanent."
    ),
    KnowledgeBaseEntry(
      "shipping",
      "Standard shipping takes 3-5 business days; tracking is emailed once an order ships."
    )
  ).map(e => normalize(e.topic) -> e).toMap

  /** Look up an entry by topic, case-insensitively; `None` when the topic is unknown. */
  def lookup(topic: String): Option[KnowledgeBaseEntry] =
    Option(topic).map(normalize).flatMap(entries.get)

  /** All known topic ids (normalized) — handy for surfacing choices to the model/tests. */
  def topics: Set[String] = entries.keySet

  private def normalize(topic: String): String = topic.trim.toLowerCase
