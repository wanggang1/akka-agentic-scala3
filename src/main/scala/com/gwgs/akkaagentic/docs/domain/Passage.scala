package com.gwgs.akkaagentic.docs.domain

/** One retrievable unit of knowledge: a body of [[text]] (embedded and matched by semantic
  * similarity) and a [[source]] label used for citation. Pure domain — no Akka, no framework.
  */
final case class Passage(source: String, text: String)
