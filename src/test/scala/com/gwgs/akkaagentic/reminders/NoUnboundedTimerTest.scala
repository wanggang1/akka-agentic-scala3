package com.gwgs.akkaagentic.reminders

import java.nio.file.{Files, Path}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T023 / FR-008 — no source in this capability may schedule a timer that can retry for ever.
  *
  * The three-argument `createSingleTimer(name, delay, deferred)` was measured still retrying at
  * 30 seconds with widening gaps (specs/017 research Q-D). The four-argument form takes an explicit
  * `maxRetries`. A rule like "always use the four-argument one" is exactly the kind a future edit can
  * quietly break, so it is a test rather than a comment — capability 12's "the agent names no rule"
  * technique, applied to FR-008.
  *
  * **No exemption list.** It reads every source under the capability's `src/main` tree, `probe/`
  * included; the probes were brought into line rather than excused (T022).
  *
  * Comments are stripped before scanning, since the scaladoc legitimately *names* the signature.
  * String literals are not parsed — none in this capability contains the call, and if one ever did the
  * test would fail loudly rather than pass silently, which is the right direction to err in.
  */
class NoUnboundedTimerTest:

  private val roots = List(
    Path.of("src/main/scala/com/gwgs/akkaagentic/reminders"),
    Path.of("src/main/java/com/gwgs/akkaagentic/reminders"))

  private val call = raw"createSingleTimer(?:Async)?\s*\(".r

  private def sources: List[Path] =
    roots.filter(Files.exists(_)).flatMap { root =>
      Files.walk(root).iterator.asScala
        .filter(p => Files.isRegularFile(p) && (p.toString.endsWith(".scala") || p.toString.endsWith(".java")))
        .toList
    }

  private def withoutComments(code: String): String =
    code.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ")

  /** Count top-level arguments starting just after an opening parenthesis: commas at depth zero, up to
    * the matching close. Brackets and braces nest too — a `Function2[A, B, C]` is not three arguments. */
  private def arity(code: String, afterOpenParen: Int): Int =
    @tailrec
    def scan(i: Int, depth: Int, commas: Int, sawContent: Boolean): Int =
      code(i) match
        case ')' if depth == 0          => if sawContent then commas + 1 else 0
        case '(' | '[' | '{'            => scan(i + 1, depth + 1, commas, true)
        case ')' | ']' | '}'            => scan(i + 1, depth - 1, commas, true)
        case ',' if depth == 0          => scan(i + 1, depth, commas + 1, true)
        case c if c.isWhitespace        => scan(i + 1, depth, commas, sawContent)
        case _                          => scan(i + 1, depth, commas, true)
    scan(afterOpenParen, 0, 0, false)

  /** Every scheduling call in the capability, as (file, arity). */
  private def schedulingCalls: List[(String, Int)] =
    sources.flatMap { path =>
      val code = withoutComments(Files.readString(path))
      call.findAllMatchIn(code).map(m => (path.getFileName.toString, arity(code, m.end))).toList
    }

  @Test
  def everySchedulingCallPassesAnExplicitMaxRetries(): Unit =
    val calls = schedulingCalls
    // Not vacuous: the capability does schedule, so the scan must have found at least that call.
    assertThat(calls.map(_._1).mkString(",")).contains("ReminderSchedulingEndpoint.java")
    val unbounded = calls.filter(_._2 != 4)
    assertThat(unbounded.map((file, n) => s"$file: $n-argument call").mkString("; ")).isEmpty()

  @Test
  def theArityCounterIsNotFooledByNestedTypesOrCalls(): Unit =
    // The counter itself, checked on the shapes it has to see through.
    val nested = "createSingleTimer(name, Duration.ofSeconds(f(a, b)), 2, method[A, B, C]((x, y) => x.go(y)).deferred(id))"
    assertThat(arity(nested, nested.indexOf('(') + 1)).isEqualTo(4)
    val three = "createSingleTimer(name, delay, deferred)"
    assertThat(arity(three, three.indexOf('(') + 1)).isEqualTo(3)
