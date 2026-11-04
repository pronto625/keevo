package com.keevo.messaging.notification.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Story 19.9b — Expo getReceipts call and answer parsing. */
@DisplayName("ExpoReceiptHttpClient (Story 19.9b)")
class ExpoReceiptHttpClientTest {

    private static final String URL = "https://exp.host/--/api/v2/push/getReceipts";

    private RestTemplate http;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        http = new RestTemplate();
        server = MockRestServiceServer.bindTo(http).build();
    }

    @Test
    @DisplayName("body {ids}, Bearer header; ok → \"\", DeviceNotRegistered, error without details → unknown, "
            + "ids without receipt absent")
    void parses() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer expo-secret"))
                .andExpect(content().json("{\"ids\":[\"a\",\"b\",\"c\",\"d\"]}"))
                .andRespond(withSuccess("{\"data\":{\"a\":{\"status\":\"ok\"},"
                        + "\"b\":{\"status\":\"error\",\"details\":{\"error\":\"DeviceNotRegistered\"}},"
                        + "\"c\":{\"status\":\"error\",\"message\":\"x\"}}}", MediaType.APPLICATION_JSON));

        var receipts = new ExpoReceiptHttpClient(http, "expo-secret").fetchReceipts(List.of("a", "b", "c", "d"));

        server.verify();
        assertThat(receipts).containsEntry("a", "").containsEntry("b", "DeviceNotRegistered")
                .containsEntry("c", "unknown").doesNotContainKey("d");
    }

    @Test
    @DisplayName("no access token → no Authorization header")
    void noToken() {
        server.expect(requestTo(URL)).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));

        assertThat(new ExpoReceiptHttpClient(http, "").fetchReceipts(List.of("a"))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("answer without data throws (tickets kept for the next run); no ids → no call")
    void missingData() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"errors\":[{\"code\":\"X\"}]}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new ExpoReceiptHttpClient(http, "").fetchReceipts(List.of("a")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new ExpoReceiptHttpClient(http, "").fetchReceipts(List.of())).isEmpty();
        server.verify();
    }
}
