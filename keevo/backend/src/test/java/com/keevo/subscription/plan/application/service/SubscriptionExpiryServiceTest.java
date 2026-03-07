package com.keevo.subscription.plan.application.service;

import com.keevo.subscription.plan.domain.port.in.DowngradeExpiredTrialsCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SubscriptionExpiryServiceTest — Unit tests for {@link SubscriptionExpiryService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionExpiryService")
class SubscriptionExpiryServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks SubscriptionExpiryService service;

    // ── M4 fix: SQL uses JDBC params (not hard-coded literals) for FREE limits ─
    // RED: service still calls update(String) with 0 args → 3-arg mock doesn't match → returns 0 → assertions fail
    // GREEN: service calls update(String, Object...) with 3 params → mocks match → assertions pass

    @Test
    @DisplayName("downgrades expired subscriptions across tenant schemas (using JDBC params for FREE limits)")
    void execute_downgrades_expiredSubscriptions() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("kv_abc123", "kv_def456"));
        // 3-arg form: update(sql, maxStores, maxProducts, maxEmployees)
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1, 0);

        int count = service.execute(new DowngradeExpiredTrialsCommand("scheduler"));

        assertThat(count).isEqualTo(1); // only first schema had expired sub
        verify(jdbcTemplate, times(2)).update(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("returns 0 when no subscriptions expired")
    void execute_returnsZero_whenNoExpirations() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("kv_abc123"));
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(0);

        int count = service.execute(new DowngradeExpiredTrialsCommand("scheduler"));

        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("skips schemas that don't match tenant schema pattern (safety guard)")
    void execute_skipsInvalidSchemaNames() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("public", "information_schema", "kv_abc123"));
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(0);

        service.execute(new DowngradeExpiredTrialsCommand("scheduler"));

        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any()); // only kv_abc123
    }

    @Test
    @DisplayName("continues processing remaining schemas even when one fails")
    void execute_continuesOnError() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("kv_abc123", "kv_def456"));
        when(jdbcTemplate.update(anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("DB error"))
                .thenReturn(1);

        int count = service.execute(new DowngradeExpiredTrialsCommand("scheduler"));

        // First schema failed silently; second succeeded
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("returns 0 and handles empty tenant list gracefully")
    void execute_returnsZero_whenNoTenants() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of());

        int count = service.execute(new DowngradeExpiredTrialsCommand("test"));

        assertThat(count).isEqualTo(0);
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }
}
