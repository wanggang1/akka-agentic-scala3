package com.gwgs.akkaagentic.wallet.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.CommandException;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.gwgs.akkaagentic.wallet.application.WalletEntity;
import com.gwgs.akkaagentic.wallet.domain.Wallet;

/**
 * HTTP surface over the wallet entity (capability 18 / A3).
 *
 * <p><strong>Why this class is Java in an otherwise-Scala capability</strong> (specs/020 research Q-D):
 * the {@code EventSourcedEntity} component client is method-reference-only
 * ({@code .method(WalletEntity::open)}) with <strong>no {@code dynamicCall}</strong>, exactly as
 * capability 6 found for the key-value entity client and capability 11 for the view client. A Scala
 * lambda compiles to a synthetic {@code $anonfun} and never resolves. The entity and its {@code Wallet}
 * state beside it are Scala — the wall travels no further than this caller.
 *
 * <p>Validation lives in the domain, so a forbidden operation reaches here as a thrown
 * {@link CommandException} (from the entity's {@code effects().error(...)}); each write maps it to
 * {@code 400}, never throwing (AGENTS.md HTTP rule). The endpoint owns its request/response records so
 * the domain {@code Wallet} never leaks over the wire (API isolation).
 */
@HttpEndpoint("/wallets")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class WalletEndpoint {

  public record OpenRequest(long startingBalance) {}

  public record AmountRequest(long amount) {}

  /** The wallet as the API shows it — mirrors {@link Wallet} without exposing the domain type. */
  public record WalletView(long balance, boolean open) {}

  private final ComponentClient componentClient;

  public WalletEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/{id}/open")
  public HttpResponse open(String id, OpenRequest request) {
    try {
      componentClient
          .forEventSourcedEntity(id)
          .method(WalletEntity::open)
          .invoke(request.startingBalance());
      return HttpResponses.created();
    } catch (CommandException e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Post("/{id}/deposit")
  public HttpResponse deposit(String id, AmountRequest request) {
    try {
      componentClient
          .forEventSourcedEntity(id)
          .method(WalletEntity::deposit)
          .invoke(request.amount());
      return HttpResponses.ok();
    } catch (CommandException e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Post("/{id}/withdraw")
  public HttpResponse withdraw(String id, AmountRequest request) {
    try {
      componentClient
          .forEventSourcedEntity(id)
          .method(WalletEntity::withdraw)
          .invoke(request.amount());
      return HttpResponses.ok();
    } catch (CommandException e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Post("/{id}/close")
  public HttpResponse close(String id) {
    try {
      componentClient.forEventSourcedEntity(id).method(WalletEntity::close).invoke();
      return HttpResponses.ok();
    } catch (CommandException e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  /** Current balance and open/closed state. A wallet with no events reads as {@code {0, false}}, not 404. */
  @Get("/{id}")
  public WalletView get(String id) {
    Wallet wallet = componentClient.forEventSourcedEntity(id).method(WalletEntity::get).invoke();
    return new WalletView(wallet.balance(), wallet.open());
  }
}
