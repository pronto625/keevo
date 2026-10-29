package com.keevo.subscription.plan.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.subscription.api.PlanChangeCause;
import com.keevo.subscription.plan.domain.model.event.PlanExpiredEvent;
import com.keevo.subscription.plan.domain.port.in.DowngradeExpiredTrialsCommand;
import com.keevo.subscription.api.PlanChangeRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SubscriptionExpiryServiceTest — Unit tests for {@link SubscriptionExpiryService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionExpiryService")
class SubscriptionExpiryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");
    private static final UUID T1 = UUID.randomUUID();
    private static final UUID T2 = UUID.randomUUID();

    @Mock JdbcTemplate jdbcTemplate;
    @Mock PlanChangeRecorder recorder;
    @Mock ApplicationEventPublisher publisher;
    @Mock TransactionTemplate tx;

    SubscriptionExpiryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(tx.execute(any(TransactionCallback.class)))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0))
                        .doInTransaction(new SimpleTransactionStatus()));
        service = new SubscriptionExpiryService(jdbcTemplate, recorder, publisher, tx,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void tenants(Object... idSchemaPairs) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < idSchemaPairs.length; i += 2) {
            rows.add(Map.of("id", idSchemaPairs[i], "schema_name", idSchemaPairs[i + 1]));
        }
        when(jdbcTemplate.queryForList(SubscriptionExpiryService.FETCH_TENANTS)).thenReturn(rows);
    }

    // ── M4 fix: SQL uses JDBC params (not hard-coded literals) for FREE limits ─

    @Test
    @DisplayName("downgrades expired subscriptions across tenant schemas (using JDBC params for FREE limits)")
    void execute_downgrades_expiredSubscriptions() {
        tenants(T1, "kv_abc123", T2, "kv_def456");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any(), any()))
                .thenReturn(List.of("PREMIUM"), List.of());

        int count = service.execute(new DowngradeExpiredTrialsCommand("scheduler"));

        assertThat(count).isEqualTo(1); // only first schema had expired sub
        verify(jdbcTemplate, times(2)).queryForList(anyString(), eq(String.class),
                eq(PlanType.FREE.getMaxStores()), eq(PlanType.FREE.getMaxProducts()),
                eq(PlanType.FREE.getMaxEmployees()));
    }

    @Test
    @DisplayName("9-2: a downgrade syncs public plan_type, records EXPIRY and publishes PlanExpiredEvent")
    void execute_publishesPlanExpiredEvent() {
        tenants(T1, "kv_abc123");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any(), any()))
                .thenReturn(List.of("PREMIUM_TRIAL"));

        service.execute(new DowngradeExpiredTrialsCommand("scheduler"));

        verify(recorder).syncPublicPlanType(T1, PlanType.FREE);
        verify(recorder).recordEvent(T1, PlanType.PREMIUM_TRIAL, PlanType.FREE, PlanChangeCause.EXPIRY, null, null);
        ArgumentCaptor<PlanExpiredEvent> event = ArgumentCaptor.forClass(PlanExpiredEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new PlanExpiredEvent(T1, "kv_abc123", PlanType.PREMIUM_TRIAL, NOW));
    }

    @Test
    @DisplayName("9-2: downgrade SQL returns the plan before the update, schema-qualified, inside a transaction")
    void execute_sqlShape() {
        tenants(T1, "kv_abc123");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any(), any())).thenReturn(List.of());

        service.execute(new DowngradeExpiredTrialsCommand("scheduler"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(String.class), any(), any(), any());
        assertThat(sql.getValue())
                .contains("\"kv_abc123\".subscriptions")
                .contains("RETURNING expired.plan_type")
                .contains("expires_at < NOW()");
        verify(tx).execute(any());
        verifyNoInteractions(recorder, publisher);
    }

    @Test
    @DisplayName("returns 0 when no subscriptions expired")
    void execute_returnsZero_whenNoExpirations() {
        tenants(T1, "kv_abc123");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any(), any())).thenReturn(List.of());

        int count = service.execute(new DowngradeExpiredTrialsCommand("scheduler"));

        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("skips schemas that don't match tenant schema pattern (safety guard)")
    void execute_skipsInvalidSchemaNames() {
        tenants(UUID.randomUUID(), "public", UUID.randomUUID(), "information_schema", T1, "kv_abc123");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any(), any())).thenReturn(List.of());

        service.execute(new DowngradeExpiredTrialsCommand("scheduler"));

        verify(jdbcTemplate, times(1)).queryForList(anyString(), eq(String.class), any(), any(), any()); // only kv_abc123
    }

    @Test
    @DisplayName("continues processing remaining schemas even when one fails")
    void execute_continuesOnError() {
        tenants(T1, "kv_abc123", T2, "kv_def456");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any(), any()))
                .thenThrow(new RuntimeException("DB error"))
                .thenReturn(List.of("PREMIUM"));

        int count = service.execute(new DowngradeExpiredTrialsCommand("scheduler"));

        // First schema failed silently; second succeeded
        assertThat(count).isEqualTo(1);
        verify(publisher).publishEvent(any(PlanExpiredEvent.class));
    }

    @Test
    @DisplayName("returns 0 and handles empty tenant list gracefully")
    void execute_returnsZero_whenNoTenants() {
        when(jdbcTemplate.queryForList(SubscriptionExpiryService.FETCH_TENANTS)).thenReturn(List.of());

        int count = service.execute(new DowngradeExpiredTrialsCommand("test"));

        assertThat(count).isEqualTo(0);
        verify(jdbcTemplate, never()).queryForList(anyString(), eq(String.class), any(), any(), any());
    }
}
