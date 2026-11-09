package com.keevo.payments.appstore.application.service;

import com.keevo.payments.appstore.adapter.out.revenuecat.DisabledRevenueCatClient;
import com.keevo.payments.appstore.domain.model.EventOutcome;
import com.keevo.payments.appstore.domain.port.out.ExternalSubscriptionStore;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AppStoreWebhookService — authentication and TRANSFER pre-read (Story 21.2)")
class AppStoreWebhookServiceTest {

    private final AppStoreSubscriptionService subscriptions = mock(AppStoreSubscriptionService.class);
    private final ExternalSubscriptionStore store = mock(ExternalSubscriptionStore.class);

    private AppStoreWebhookService service(String secret, RevenueCatClient client) {
        return new AppStoreWebhookService(subscriptions, store, client, new AppStoreSettings(secret, false, null));
    }

    private static Map<String, Object> event(String type, Map<String, Object> extra) {
        java.util.HashMap<String, Object> e = new java.util.HashMap<>(extra);
        e.put("id", "E-1");
        e.put("type", type);
        return Map.of("event", e);
    }

    @Test
    @DisplayName("exact secret only: wrong, missing, or no secret configured → UNAUTHORIZED, nothing processed")
    void secret() {
        AppStoreWebhookService s = service("Bearer abc", new DisabledRevenueCatClient());
        assertThat(s.handle("Bearer abd", event("TEST", Map.of()))).isEqualTo(AppStoreWebhookService.Result.UNAUTHORIZED);
        assertThat(s.handle(null, event("TEST", Map.of()))).isEqualTo(AppStoreWebhookService.Result.UNAUTHORIZED);
        assertThat(s.handle("Bearer abc ", event("TEST", Map.of())))
                .isEqualTo(AppStoreWebhookService.Result.UNAUTHORIZED);
        assertThat(service("", new DisabledRevenueCatClient()).handle("", event("TEST", Map.of())))
                .isEqualTo(AppStoreWebhookService.Result.UNAUTHORIZED);
        verifyNoInteractions(subscriptions, store);
        when(subscriptions.process(any(), anyMap())).thenReturn(EventOutcome.TEST);
        assertThat(s.handle("Bearer abc", event("TEST", Map.of()))).isEqualTo(AppStoreWebhookService.Result.OK);
        assertThat(s.handle("Bearer abc", Map.of("nothing", 1))).isEqualTo(AppStoreWebhookService.Result.OK);
    }

    @Test
    @DisplayName("TRANSFER with RevenueCat disabled → RETRY (redelivered), not claimed; already processed → OK, no call")
    void transfer() {
        String user = UUID.randomUUID().toString();
        Map<String, Object> body = event("TRANSFER", Map.of("transferred_from", List.of(user),
                "transferred_to", List.of(UUID.randomUUID().toString())));
        AppStoreWebhookService s = service("k", new DisabledRevenueCatClient());
        when(store.eventExists("E-1")).thenReturn(false);
        assertThat(s.handle("k", body)).isEqualTo(AppStoreWebhookService.Result.RETRY);
        verify(subscriptions, never()).process(any(), anyMap());

        RevenueCatClient client = mock(RevenueCatClient.class);
        when(store.eventExists("E-1")).thenReturn(true);
        assertThat(service("k", client).handle("k", body)).isEqualTo(AppStoreWebhookService.Result.OK);
        verifyNoInteractions(client);
        verify(subscriptions, never()).process(any(), anyMap());
    }
}
