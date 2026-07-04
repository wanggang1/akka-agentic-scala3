package com.gwgs.akkaagentic.team.application;

/**
 * Composer agent input: carries the pre-detected {@code tone} (from the tone step) so the composer
 * adapts the greeting to it rather than re-classifying the message. {@code timezone} is nullable.
 * Java-shaped wire record (research R3).
 */
public record ComposeRequest(String user, String text, String tone, String timezone) {}
