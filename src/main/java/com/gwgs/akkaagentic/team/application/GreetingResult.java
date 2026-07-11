package com.gwgs.akkaagentic.team.application;

/**
 * The structured greeting: the composer parses it from the model reply, the workflow stores it in
 * its state, and {@code getResult} returns it. The endpoint maps it to its own {@code GreetReply}
 * so the wire contract stays independent of this application type (API isolation). Java-shaped
 * wire record (research R3).
 */
public record GreetingResult(String greeting, String tone, String timeOfDay) {}
