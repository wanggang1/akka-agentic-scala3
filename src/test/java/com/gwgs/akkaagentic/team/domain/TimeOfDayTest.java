package com.gwgs.akkaagentic.team.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure {@link TimeOfDay} domain helper (no Akka, deterministic). */
class TimeOfDayTest {

  private static String labelAtUtcHour(int hour) {
    // An instant whose UTC-local hour is exactly `hour`.
    Instant instant = Instant.parse(String.format("2026-01-01T%02d:30:00Z", hour));
    return TimeOfDay.of(instant, ZoneId.of("UTC"));
  }

  @Test
  void labelsByHourBoundaries() {
    assertThat(labelAtUtcHour(4)).isEqualTo("night");
    assertThat(labelAtUtcHour(5)).isEqualTo("morning");
    assertThat(labelAtUtcHour(11)).isEqualTo("morning");
    assertThat(labelAtUtcHour(12)).isEqualTo("afternoon");
    assertThat(labelAtUtcHour(16)).isEqualTo("afternoon");
    assertThat(labelAtUtcHour(17)).isEqualTo("evening");
    assertThat(labelAtUtcHour(20)).isEqualTo("evening");
    assertThat(labelAtUtcHour(21)).isEqualTo("night");
    assertThat(labelAtUtcHour(0)).isEqualTo("night");
  }

  @Test
  void appliesTheZoneOffset() {
    // 23:30Z is 18:30 in America/New_York (UTC-5 in January) -> evening.
    Instant instant = Instant.parse("2026-01-01T23:30:00Z");
    assertThat(TimeOfDay.of(instant, ZoneId.of("America/New_York"))).isEqualTo("evening");
  }

  @Test
  void nowFallsBackToUtcForAbsentBlankOrInvalidZone() {
    // Each falls back to UTC, so all agree with an explicit "UTC" call made at ~the same instant.
    String utcNow = TimeOfDay.now("UTC");
    assertThat(TimeOfDay.now(null)).isEqualTo(utcNow);
    assertThat(TimeOfDay.now("")).isEqualTo(utcNow);
    assertThat(TimeOfDay.now("   ")).isEqualTo(utcNow);
    assertThat(TimeOfDay.now("Not/AZone")).isEqualTo(utcNow);
  }

  @Test
  void nowHonorsAValidZoneAndNeverThrows() {
    assertThat(TimeOfDay.now("America/New_York"))
        .isIn("morning", "afternoon", "evening", "night");
  }
}
