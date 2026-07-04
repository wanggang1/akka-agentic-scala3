package com.gwgs.akkaagentic.team.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.Location;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gwgs.akkaagentic.team.application.GreetingResult;
import com.gwgs.akkaagentic.team.application.GreetingWorkflow;
import com.gwgs.akkaagentic.team.application.StartGreeting;
import java.util.UUID;

/**
 * Async HTTP surface for capability 2 (the multi-agent workflow). {@code POST /greetings} starts the
 * workflow and returns a handle immediately; {@code GET /greetings/{id}} retrieves the composed
 * greeting once ready. Separate from — and independent of — capability 1's {@code POST /greet}.
 *
 * <p>Java records are used for the wire types (Java-shaped by construction). The endpoint owns its
 * {@code StartRequest}/{@code GreetReply} so the HTTP contract stays independent of the workflow and
 * agent types (API isolation).
 */
@HttpEndpoint
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class GreetingTeamEndpoint {

  /** Inbound body. Unknown properties tolerated (contract C9). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StartRequest(String user, String text, String timezone) {}

  /** POST acknowledgement — the handle to poll. */
  public record StartAccepted(String id) {}

  /** Outbound greeting — API-owned, mirrors {@link GreetingResult}. */
  public record GreetReply(String greeting, String tone, String timeOfDay) {}

  private final ComponentClient componentClient;

  public GreetingTeamEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/greetings")
  public HttpResponse start(StartRequest request) {
    if (isBlank(request.user())) {
      return HttpResponses.badRequest("user must not be blank");
    }
    if (isBlank(request.text())) {
      return HttpResponses.badRequest("text must not be blank");
    }

    var id = UUID.randomUUID().toString();
    componentClient
        .forWorkflow(id)
        .method(GreetingWorkflow::start)
        .invoke(new StartGreeting(request.user(), request.text(), request.timezone()));

    // 202 Accepted: the greeting is not ready yet; Location points at the eventual result.
    return HttpResponses.accepted(new StartAccepted(id))
        .addHeader(Location.create("/greetings/" + id));
  }

  @Get("/greetings/{id}")
  public HttpResponse get(String id) {
    try {
      GreetingResult result =
          componentClient.forWorkflow(id).method(GreetingWorkflow::getResult).invoke();
      return HttpResponses.ok(toApi(result));
    } catch (RuntimeException notReady) {
      // getResult errors until the workflow has COMPLETED, and for an unknown/never-started id.
      return HttpResponses.notFound("greeting not ready");
    }
  }

  private static GreetReply toApi(GreetingResult r) {
    return new GreetReply(r.greeting(), r.tone(), r.timeOfDay());
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
