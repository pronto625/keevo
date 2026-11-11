package com.keevo.crm.contact.adapter.out.persistence;

import com.keevo.crm.contact.domain.port.out.ClientPurchaseStatsPort.Stats;
import com.keevo.shared.capability.revenue.RevenueQueryPort;
import com.keevo.shared.capability.revenue.RevenueQueryPort.Relations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 20.2 reprise 11/11/2026 — the SQL shape of {@link JdbcClientPurchaseStats} (Mockito, no DB). The figures
 * themselves are checked on PostgreSQL by {@code RevenueSourceAcceptancePostgresIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JdbcClientPurchaseStats")
class JdbcClientPurchaseStatsTest {

    private static final Relations ALL = new Relations("(ALL_DOCS)", "(ALL_LINES)");
    private static final Relations SCOPED = new Relations("(SCOPED_DOCS)", "(SCOPED_LINES)");
    private static final UUID CLIENT = UUID.randomUUID();

    @Mock JdbcTemplate jdbc;
    @Mock RevenueQueryPort revenue;

    @Test
    @DisplayName("every store: the full source, client filter only, the shared aggregate")
    @SuppressWarnings("unchecked")
    void everyStore() {
        when(revenue.current()).thenReturn(ALL);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new Stats(2, 13_000, null));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        Stats stats = new JdbcClientPurchaseStats(jdbc, revenue).statsOf(CLIENT, null);

        assertThat(stats.purchaseCount()).isEqualTo(2);
        verify(jdbc).queryForObject(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).isEqualTo("SELECT " + ClientPurchaseAggregate.select("r")
                + " FROM (ALL_DOCS) r WHERE r.client_id = ?");
        verify(revenue, never()).storeScoped();
    }

    @Test
    @DisplayName("store set: the store-scoped source and store_id = ANY(...)")
    @SuppressWarnings("unchecked")
    void storeSet() {
        when(revenue.storeScoped()).thenReturn(SCOPED);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(Stats.NONE);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        UUID store = UUID.randomUUID();

        new JdbcClientPurchaseStats(jdbc, revenue).statsOf(CLIENT, Set.of(store));

        verify(jdbc).queryForObject(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("FROM (SCOPED_DOCS) r WHERE r.client_id = ?")
                .endsWith(" AND r.store_id = ANY(CAST(? AS uuid[]))");
        verify(revenue, never()).current();
    }

    @Test
    @DisplayName("empty store set: no purchase, no SQL")
    void emptyStoreSet() {
        assertThat(new JdbcClientPurchaseStats(jdbc, revenue).statsOf(CLIENT, Set.of())).isEqualTo(Stats.NONE);
        verifyNoInteractions(jdbc, revenue);
    }

    @Test
    @DisplayName("aggregate: count = SUM(doc_count), net total = SUM(total_amount), last = purchases only")
    void aggregateDefinition() {
        assertThat(ClientPurchaseAggregate.select("r")).isEqualTo(
                "COALESCE(SUM(r.doc_count), 0) AS purchase_count, COALESCE(SUM(r.total_amount), 0) AS total_spent, "
                        + "MAX(COALESCE(r.recorded_at, r.created_at)) FILTER (WHERE r.doc_count > 0) "
                        + "AS last_purchase_at");
    }
}
