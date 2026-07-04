package com.gwgs.akkaagentic.team.domain;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Pure, total time-of-day labels for capability 2 (the multi-agent workflow).
 *
 * <p>This is a deliberate Java copy of capability 1's Scala {@code TimeOfDay} (see
 * {@code com.gwgs.akkaagentic.domain.TimeOfDay}). Duplicating ~15 lines keeps this Java module
 * fully decoupled from the Scala sources — reusing the Scala version from Java would mean
 * {@code TimeOfDay$.MODULE$} calls and {@code scala.Option} juggling. See
 * {@code specs/004-multi-agent-workflow/research.md} (R3) and the plan's Complexity Tracking.
 *
 * <p>No Akka dependencies, so it is deterministically unit-testable. Labels by local hour:
 * morning 05–11, afternoon 12–16, evening 17–20, night 21–04. Never throws — a {@code null},
 * blank, or unrecognized timezone falls back to UTC.
 */
public final class TimeOfDay {

  private static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");

  private TimeOfDay() {}

  /** The time-of-day label for {@code instant} observed in {@code zone}. Total: every hour maps. */
  public static String of(Instant instant, ZoneId zone) {
    int hour = instant.atZone(zone).getHour();
    if (hour >= 5 && hour <= 11) return "morning";
    if (hour >= 12 && hour <= 16) return "afternoon";
    if (hour >= 17 && hour <= 20) return "evening";
    return "night"; // 21–23 and 0–4
  }

  /**
   * The current time-of-day for the given IANA timezone id (e.g. {@code "America/New_York"}).
   * A {@code null}, blank, or unrecognized id falls back to UTC. Never throws.
   */
  public static String now(String timezone) {
    return of(Instant.now(), resolveZone(timezone));
  }

  /** Resolve an IANA timezone id, defaulting to UTC on null, blank, or unrecognized input. */
  private static ZoneId resolveZone(String timezone) {
    if (timezone == null || timezone.isBlank()) return DEFAULT_ZONE;
    try {
      return ZoneId.of(timezone.trim());
    } catch (RuntimeException e) {
      return DEFAULT_ZONE;
    }
  }
}
