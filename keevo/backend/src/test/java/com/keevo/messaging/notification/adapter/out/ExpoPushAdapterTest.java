package com.keevo.messaging.notification.adapter.out;

import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.messaging.notification.domain.port.out.ExpoPushTicketRepository;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushSendResult;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("ExpoPushAdapter — Expo push delivery channel (Story 19.9)")
class ExpoPushAdapterTest {

    private static final String URL = "https://exp.host/--/api/v2/push/send";
    private static final String TENANT = "kv_abc123";
    private static final NotificationPayload PAYLOAD = NotificationPayload.of("STOCK_ALERT", "Stock bas",
            "Riz : 2 restants", "/stock/1", Map.of("productId", "p1"));

    private RestTemplate http;
    private MockRestServiceServer server;
    private DeviceTokenRepository tokens;
    private ExpoPushTicketRepository tickets;

    @BeforeEach
    void setUp() {
        http = new RestTemplate();
        server = MockRestServiceServer.bindTo(http).build();
        tokens = mock(DeviceTokenRepository.class);
        tickets = mock(ExpoPushTicketRepository.class);
    }

    private static String okTickets(int n) {
        return "{\"data\":[" + String.join(",", Collections.nCopies(n, "{\"status\":\"ok\"}")) + "]}";
    }

    @Test
    @DisplayName("supports only Expo tokens")
    void supports() {
        ExpoPushAdapter adapter = new ExpoPushAdapter(http, tokens, tickets, "");
        assertThat(adapter.supports("ExponentPushToken[a]")).isTrue();
        assertThat(adapter.supports("ExpoPushToken[a]")).isTrue();
        assertThat(adapter.supports("fcm-token")).isFalse();
        assertThat(adapter.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("body shape; DeviceNotRegistered tokens deleted in one statement, in the tenant")
    void bodyAndUnregistered() {
        List<String> tenantDuringDelete = new ArrayList<>();
        when(tokens.deleteByTokens(any())).thenAnswer(inv -> {
            tenantDuringDelete.add(TenantContext.getCurrentTenant());
            return 2;
        });
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().json("[{\"to\":\"ExponentPushToken[aaa]\",\"title\":\"Stock bas\","
                        + "\"body\":\"Riz : 2 restants\",\"sound\":\"default\",\"priority\":\"high\","
                        + "\"channelId\":\"default\",\"data\":{\"productId\":\"p1\",\"deepLink\":\"/stock/1\","
                        + "\"type\":\"STOCK_ALERT\"}},{\"to\":\"ExpoPushToken[bbb]\"},{\"to\":\"ExpoPushToken[ccc]\"}]"))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\",\"id\":\"t1\"},{\"status\":\"error\","
                        + "\"details\":{\"error\":\"DeviceNotRegistered\"}},{\"status\":\"error\","
                        + "\"details\":{\"error\":\"DeviceNotRegistered\"}}]}", MediaType.APPLICATION_JSON));

        PushSendResult result = new ExpoPushAdapter(http, tokens, tickets, "").send(TENANT,
                List.of("ExponentPushToken[aaa]", "ExpoPushToken[bbb]", "ExpoPushToken[ccc]"), PAYLOAD);

        server.verify();
        assertThat(result).isEqualTo(new PushSendResult(1, 2, 1, 0));
        verify(tokens).deleteByTokens(List.of("ExpoPushToken[bbb]", "ExpoPushToken[ccc]"));
        verify(tokens, never()).deleteByToken(any());
        assertThat(tenantDuringDelete).containsExactly(TENANT);
        assertThat(TenantContext.getCurrentTenant()).isNull();
        // Story 19.9b — the ok ticket is stored with its token for the receipt job
        verify(tickets).saveAll(TENANT, Map.of("t1", "ExponentPushToken[aaa]"));
    }

    @Test
    @DisplayName("Story 19.9b — ok tickets of every chunk stored in one batch; storage failure never throws")
    void ticketsStoredOncePerSend() {
        List<String> expo = IntStream.range(0, 101).mapToObj(i -> "ExponentPushToken[" + i + "]").toList();
        String ids100 = "{\"data\":[" + String.join(",", IntStream.range(0, 100)
                .mapToObj(i -> "{\"status\":\"ok\",\"id\":\"id" + i + "\"}").toList()) + "]}";
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(ids100, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\",\"id\":\"id100\"}]}",
                        MediaType.APPLICATION_JSON));
        doThrow(new IllegalStateException("db down")).when(tickets).saveAll(any(), any());

        PushSendResult result = new ExpoPushAdapter(http, tokens, tickets, "").send(TENANT, expo, PAYLOAD);

        assertThat(result.successCount()).isEqualTo(101);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, String>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(tickets, times(1)).saveAll(org.mockito.ArgumentMatchers.eq(TENANT), captor.capture());
        assertThat(captor.getValue()).hasSize(101).containsEntry("id100", "ExponentPushToken[100]");
    }

    @Test
    @DisplayName("Bearer header when an access token is configured")
    void accessToken() {
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Bearer expo-secret"))
                .andRespond(withSuccess(okTickets(1), MediaType.APPLICATION_JSON));

        new ExpoPushAdapter(http, tokens, tickets, "expo-secret").send(TENANT, List.of("ExponentPushToken[a]"), PAYLOAD);

        server.verify();
    }

    @Test
    @DisplayName("chunks of 100, results summed")
    void chunks() {
        List<String> expo = IntStream.range(0, 150).mapToObj(i -> "ExponentPushToken[" + i + "]").toList();
        String ok50 = "{\"data\":[" + String.join(",", Collections.nCopies(49, "{\"status\":\"ok\"}"))
                + ",{\"status\":\"error\",\"details\":{\"error\":\"MessageRateExceeded\"}}]}";
        server.expect(ExpectedCount.once(), requestTo(URL)).andExpect(jsonPath("$.length()").value(100))
                .andRespond(withSuccess(okTickets(100), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(URL)).andExpect(jsonPath("$.length()").value(50))
                .andRespond(withSuccess(ok50, MediaType.APPLICATION_JSON));

        PushSendResult result = new ExpoPushAdapter(http, tokens, tickets, "").send(TENANT, expo, PAYLOAD);

        server.verify();
        assertThat(result).isEqualTo(new PushSendResult(149, 1, 2, 0));
        verify(tokens, never()).deleteByTokens(any());
        verify(tickets, never()).saveAll(any(), any()); // ok tickets without an id: nothing to store
    }

    @Test
    @DisplayName("2xx with a top-level errors array and no data → whole chunk failed")
    void requestLevelErrors() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"errors\":[{\"code\":\"PUSH_TOO_MANY_EXPERIENCE_IDS\",\"message\":\"x\"}]}",
                MediaType.APPLICATION_JSON));

        assertThat(new ExpoPushAdapter(http, tokens, tickets, "").send(TENANT,
                List.of("ExponentPushToken[a]", "ExponentPushToken[b]"), PAYLOAD))
                .isEqualTo(new PushSendResult(0, 2, 1, 1));
    }

    @Test
    @DisplayName("5xx → never throws, the chunk counted as failed")
    void serverError() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(new ExpoPushAdapter(http, tokens, tickets, "").send(TENANT, List.of("ExponentPushToken[x]"), PAYLOAD))
                .isEqualTo(new PushSendResult(0, 1, 1, 1));
    }
}
