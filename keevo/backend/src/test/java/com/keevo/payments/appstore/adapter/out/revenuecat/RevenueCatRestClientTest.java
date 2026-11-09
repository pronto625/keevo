package com.keevo.payments.appstore.adapter.out.revenuecat;

import com.keevo.payments.appstore.domain.port.out.RevenueCatClient.RevenueCatUnavailableException;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient.StoreSubscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("RevenueCatRestClient — RevenueCat REST v2, no real network (Story 21.2)")
class RevenueCatRestClientTest {

    private static final String BASE = "https://rc.test";
    private MockRestServiceServer server;
    private RevenueCatRestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE).defaultHeader("Authorization", "Bearer sk_x");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RevenueCatRestClient(builder.build(), "proj1",
                Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("subscriptions: product map paged (next_page) and cached; store identifier, original transaction, "
            + "access, expiry, sandbox")
    void subscriptions() {
        server.expect(requestTo(BASE + "/v2/projects/proj1/customers/u-1/subscriptions?limit=100"))
                .andExpect(header("Authorization", "Bearer sk_x"))
                .andRespond(withSuccess("{\"items\":[{\"product_id\":\"prod_a\",\"store\":\"app_store\","
                        + "\"gives_access\":true,\"current_period_ends_at\":1767225600000,"
                        + "\"store_subscription_identifier\":\"2000\",\"environment\":\"sandbox\"},"
                        + "{\"product_id\":\"prod_unknown\",\"store_subscription_identifier\":\"3000\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v2/projects/proj1/products?limit=100"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"prod_a\",\"store_identifier\":\"brefpoint.pro.month\"}],"
                        + "\"next_page\":\"/v2/projects/proj1/products?limit=100&starting_after=prod_a\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v2/projects/proj1/products?limit=100&starting_after=prod_a"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"prod_b\",\"store_identifier\":\"brefpoint.pro.yearly\"}]}",
                        MediaType.APPLICATION_JSON));
        // the unknown product would force a refresh, but the map was loaded less than a minute ago: no reload
        server.expect(requestTo(BASE + "/v2/projects/proj1/customers/u-2/subscriptions?limit=100"))
                .andRespond(withSuccess("{\"items\":[{\"product_id\":\"prod_a\",\"store\":\"app_store\","
                        + "\"gives_access\":false,\"store_subscription_identifier\":\"4000\","
                        + "\"environment\":\"production\",\"auto_renewal_status\":\"will_not_renew\","
                        + "\"status\":\"in_grace_period\"}]}", MediaType.APPLICATION_JSON));

        List<StoreSubscription> subs = client.subscriptions("u-1");
        assertThat(subs).containsExactly(new StoreSubscription("2000", "brefpoint.pro.month", "APP_STORE", true,
                Instant.ofEpochMilli(1767225600000L), true));
        List<StoreSubscription> second = client.subscriptions("u-2"); // map cached (TTL): no product call
        assertThat(second).singleElement().satisfies(s -> {
            assertThat(s.givesAccess()).isFalse();
            assertThat(s.sandbox()).isFalse();
            assertThat(s.expiresAt()).isNull();
            assertThat(s.autoRenew()).isFalse();
            assertThat(s.inGrace()).isTrue();
        });
        server.verify();
    }

    @Test
    @DisplayName("an HTTP error → RevenueCatUnavailableException with the status only (no key, no body)")
    void error() {
        server.expect(requestTo(BASE + "/v2/projects/proj1/customers/u-1/subscriptions?limit=100"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\":\"bad key sk_x\"}"));
        assertThatThrownBy(() -> client.subscriptions("u-1")).isInstanceOf(RevenueCatUnavailableException.class)
                .hasMessage("RevenueCat HTTP 401")
                .satisfies(e -> assertThat(((RevenueCatUnavailableException) e).disabled()).isFalse());
    }
}
