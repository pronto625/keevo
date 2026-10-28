package com.keevo.crm.contact.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ClientSalesStatsAdapter (Story 2.5) — Mockito, no DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClientSalesStatsAdapter")
class ClientSalesStatsAdapterTest {

    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks ClientSalesStatsAdapter adapter;

    private static final UUID CLIENT_ID = UUID.randomUUID();

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentTenant("kv_abc123");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Story 11.4 AC7: an invalid tenant schema returns 0 without building any SQL")
    void should_return_zero_without_sql_when_schema_invalid() {
        TenantContext.setCurrentTenant("public");

        assertThat(adapter.countSalesByClient(CLIENT_ID)).isZero();
        assertThat(adapter.totalSpentByClient(CLIENT_ID)).isZero();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("countSalesByClient() returns count from JDBC query")
    void should_count_sales_for_client() {
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*)"),
                eq(Long.class),
                eq(CLIENT_ID)
        )).thenReturn(5L);

        long count = adapter.countSalesByClient(CLIENT_ID);

        assertThat(count).isEqualTo(5L);
    }

    @Test
    @DisplayName("countSalesByClient() returns 0 when JDBC returns null")
    void should_return_zero_when_count_null() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any()))
                .thenReturn(null);

        assertThat(adapter.countSalesByClient(CLIENT_ID)).isZero();
    }

    @Test
    @DisplayName("totalSpentByClient() returns sum from JDBC query")
    void should_sum_total_spent_for_client() {
        when(jdbcTemplate.queryForObject(
                contains("SUM(total_amount)"),
                eq(Long.class),
                eq(CLIENT_ID)
        )).thenReturn(150000L);

        long total = adapter.totalSpentByClient(CLIENT_ID);

        assertThat(total).isEqualTo(150_000L);
    }

    @Test
    @DisplayName("totalSpentByClient() returns 0 when JDBC returns null (no sales)")
    void should_return_zero_when_sum_null() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any()))
                .thenReturn(null);

        assertThat(adapter.totalSpentByClient(CLIENT_ID)).isZero();
    }
}
