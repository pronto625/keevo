package com.keevo.commerce.sale.adapter.out;

import com.keevo.commerce.sale.adapter.out.persistence.impl.JdbcSalesSummary;
import com.keevo.commerce.sale.domain.port.out.SalesSummaryPort.Summary;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story P03 — SQL shape of {@link JdbcSalesSummary} (Mockito, no DB). Figures on PostgreSQL:
 * {@code RevenueSourceAcceptancePostgresIntegrationTest#salesSummaryBridgedOnce}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P03 — JdbcSalesSummary")
class JdbcSalesSummaryTest {

    private static final Relations ALL = new Relations("(ALL_DOCS)", "(ALL_LINES)");
    private static final Relations SCOPED = new Relations("(SCOPED_DOCS)", "(SCOPED_LINES)");
    private static final Instant FROM = Instant.parse("2026-11-26T23:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-27T23:00:00Z");

    @Mock JdbcTemplate jdbc;
    @Mock RevenueQueryPort revenue;

    @Test
    @DisplayName("every store: the full source, period only, one aggregate")
    @SuppressWarnings("unchecked")
    void everyStore() {
        when(revenue.current()).thenReturn(ALL);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new Summary(7_500, 2, 7_500));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        Summary s = new JdbcSalesSummary(jdbc, revenue).summarize(FROM, TO, null, null);

        assertThat(s.amount()).isEqualTo(7_500);
        verify(jdbc).queryForObject(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT COALESCE(SUM(r.total_amount), 0) AS amount, "
                + "COALESCE(SUM(r.doc_count), 0) AS doc_count, COALESCE(SUM(r.paid_amount), 0) AS paid_amount "
                + "FROM (ALL_DOCS) r WHERE r.occurred_at >= ? AND r.occurred_at < ?");
        assertThat(args.getValue()).hasSize(2);
        verify(revenue, never()).storeScoped();
    }

    @Test
    @DisplayName("one store and one seller: the store-scoped source with both predicates")
    @SuppressWarnings("unchecked")
    void storeAndSeller() {
        when(revenue.storeScoped()).thenReturn(SCOPED);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(Summary.NONE);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        UUID store = UUID.randomUUID();
        UUID seller = UUID.randomUUID();

        new JdbcSalesSummary(jdbc, revenue).summarize(FROM, TO, store, seller);

        verify(jdbc).queryForObject(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("FROM (SCOPED_DOCS) r WHERE")
                .endsWith(" AND r.store_id = ? AND r.employee_id = ?");
        assertThat(args.getValue()).hasSize(4).endsWith(store, seller);
        verify(revenue, never()).current();
    }
}
