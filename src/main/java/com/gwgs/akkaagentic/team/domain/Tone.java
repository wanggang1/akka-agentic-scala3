package com.gwgs.akkaagentic.team.domain;

/**
 * The tone/intent label produced by the tone-detection agent and consumed by the composer.
 *
 * <p>Parse-don't-validate: {@link #normalize} yields a clean label (or the neutral default) so
 * downstream code never re-checks for blankness. Pure; no Akka dependencies.
 */
public final class Tone {

  /** Fallback label used when tone detection is absent, blank, or fails over. */
  public static final String NEUTRAL = "neutral";

  private Tone() {}

  /** Trim a raw label; a {@code null} or blank value becomes {@link #NEUTRAL}. */
  public static String normalize(String raw) {
    if (raw == null) return NEUTRAL;
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? NEUTRAL : trimmed;
  }
}
