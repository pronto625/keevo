package com.keevo.payments.appstore.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AppStoreEvent — tolerant reading of a RevenueCat webhook body (Story 21.2)")
class AppStoreEventTest {

    @Test
    @DisplayName("reads the fields RevenueCat sends (numbers as numbers or strings, upper-cased codes, attribute)")
    void parse() {
        UUID user = UUID.randomUUID();
        Map<String, Object> event = new HashMap<>();
        event.put("id", "EVT-1");
        event.put("type", "renewal");
        event.put("app_user_id", user.toString());
        event.put("original_transaction_id", "1000");
        event.put("transaction_id", "1001");
        event.put("product_id", "brefpoint.pro.month");
        event.put("store", "app_store");
        event.put("environment", "SANDBOX");
        event.put("event_timestamp_ms", "1700000000000");
        event.put("expiration_at_ms", 1700000100000L);
        event.put("grace_period_expiration_at_ms", 1700000200000L);
        event.put("cancel_reason", "CUSTOMER_SUPPORT");
        event.put("price_in_purchased_currency", 2.5);
        event.put("currency", "eur");
        event.put("transferred_to", List.of("a", "b"));
        event.put("subscriber_attributes", Map.of("keevo_tenant", Map.of("value", "kv-abc123")));

        AppStoreEvent e = AppStoreEvent.parse(Map.of("api_version", "1.0", "event", event)).orElseThrow();

        assertThat(e.type()).isEqualTo("RENEWAL");
        assertThat(e.store()).isEqualTo("APP_STORE");
        assertThat(e.appUser()).contains(user);
        assertThat(e.sandbox()).isTrue();
        assertThat(e.refund()).isTrue();
        assertThat(e.eventTimestampMs()).isEqualTo(1700000000000L);
        assertThat(e.expiresAt()).isEqualTo(Instant.ofEpochMilli(1700000100000L));
        assertThat(e.graceExpiresAt()).isEqualTo(Instant.ofEpochMilli(1700000200000L));
        assertThat(e.priceInPurchasedCurrency()).isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(e.currency()).isEqualTo("EUR");
        assertThat(e.transferredTo()).containsExactly("a", "b");
        assertThat(e.tenantCode()).isEqualTo("KV-ABC123");
    }

    @Test
    @DisplayName("no event, no id or no type → empty; an anonymous customer has no Keevo user")
    void unusable() {
        assertThat(AppStoreEvent.parse(null)).isEmpty();
        assertThat(AppStoreEvent.parse(Map.of("event", "x"))).isEmpty();
        assertThat(AppStoreEvent.parse(Map.of("event", Map.of("type", "TEST")))).isEmpty();
        AppStoreEvent e = AppStoreEvent.parse(Map.of("event", Map.of("id", "E", "type", "TEST",
                "app_user_id", "$RCAnonymousID:abc", "event_timestamp_ms", "x"))).orElseThrow();
        assertThat(e.appUser()).isEmpty();
        assertThat(e.eventTimestampMs()).isZero();
        assertThat(e.expiresAt()).isNull();
    }
}
