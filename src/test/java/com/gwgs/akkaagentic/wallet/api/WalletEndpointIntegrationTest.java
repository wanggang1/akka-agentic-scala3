package com.gwgs.akkaagentic.wallet.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.http.javadsl.model.StatusCodes;
import com.gwgs.akkaagentic.wallet.api.WalletEndpoint.AmountRequest;
import com.gwgs.akkaagentic.wallet.api.WalletEndpoint.OpenRequest;
import com.gwgs.akkaagentic.wallet.api.WalletEndpoint.WalletView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 over real HTTP (capability 18): a wallet records its history and reports a folded balance.
 *
 * <p>Java (not Scala) by the same wall the endpoint is Java for — but these tests drive the endpoint over
 * HTTP via {@code httpClient}, so the choice here is only to sit beside the Java endpoint. The service
 * boots the whole runtime, so this also proves the Scala entity is discovered and the Java event
 * hierarchy passes startup validation.
 */
public class WalletEndpointIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withAdditionalConfig(
        "akka.javasdk.agent.googleai-gemini.api-key = n/a");
  }

  private WalletView get(String id) {
    return httpClient.GET("/wallets/" + id).responseBodyAs(WalletView.class).invoke().body();
  }

  @Test
  public void opensDepositsWithdrawsAndReadsTheFoldedBalance() {
    var id = "alice";

    var opened =
        httpClient.POST("/wallets/" + id + "/open").withRequestBody(new OpenRequest(100)).invoke();
    assertThat(opened.status()).isEqualTo(StatusCodes.CREATED);

    httpClient.POST("/wallets/" + id + "/deposit").withRequestBody(new AmountRequest(50)).invoke();
    httpClient.POST("/wallets/" + id + "/withdraw").withRequestBody(new AmountRequest(30)).invoke();

    var view = get(id);
    assertThat(view.balance()).isEqualTo(120L); // 100 + 50 - 30, reached by folding three events
    assertThat(view.open()).isTrue();
  }

  @Test
  public void aWalletWithNoEventsReadsAsEmptyStateNotNotFound() {
    var view = get("nobody-ever-touched-this");
    assertThat(view.balance()).isEqualTo(0L);
    assertThat(view.open()).isFalse();
  }

  @Test
  public void closingLeavesTheWalletClosed() {
    var id = "carol";
    httpClient.POST("/wallets/" + id + "/open").withRequestBody(new OpenRequest(10)).invoke();
    var closed = httpClient.POST("/wallets/" + id + "/close").invoke();
    assertThat(closed.status()).isEqualTo(StatusCodes.OK);

    assertThat(get(id).open()).isFalse();
    assertThat(get(id).balance()).isEqualTo(10L);
  }
}
