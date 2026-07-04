package com.gwgs.akkaagentic.team.application;

/**
 * Workflow command: the validated inputs to start a greeting.
 *
 * <p>{@code user} and {@code text} are guaranteed non-blank (the endpoint validates them before
 * starting the workflow); {@code timezone} is nullable (absent/blank/invalid → UTC downstream).
 * A plain Java record — Java-shaped by construction, so it serializes through the SDK's internal
 * component serializer with no annotations (see {@code specs/004} research R3), unlike capability
 * 1's Scala agent types which need explicit {@code @JsonCreator}/{@code @JsonProperty}.
 */
public record StartGreeting(String user, String text, String timezone) {}
