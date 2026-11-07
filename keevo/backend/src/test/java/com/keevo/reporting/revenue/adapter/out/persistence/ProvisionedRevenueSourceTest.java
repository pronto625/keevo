package com.keevo.reporting.revenue.adapter.out.persistence;

import com.keevo.reporting.revenue.domain.port.out.RevenueQueryPort.Relations;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 20.9 — the revenue source's branches follow the tables present in the tenant schema (their data exists),
 * never the module's current activation; it never provisions nor fails.
 */
class ProvisionedRevenueSourceTest {

    private static final String TENANT = "kv_abc123";
    private static final String CHECK = ProvisionedRevenueSource.INVOICING_TABLES_PRESENT;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProvisionedRevenueSource source = new ProvisionedRevenueSource(jdbc);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("invoicing tables present: sales, invoices and credit notes; found once, then cached per tenant")
    void tablesPresent() {
        TenantContext.setCurrentTenant(TENANT);
        when(jdbc.queryForObject(CHECK, Boolean.class)).thenReturn(true);

        Relations r = source.current();
        assertThat(r.documents()).contains("FROM sales s").contains("FROM invoices i").contains("FROM credit_notes cn");
        assertThat(r.lines()).contains("FROM sale_items si").contains("FROM invoice_items it")
                .contains("FROM credit_note_items cni");
        assertThat(source.current()).isEqualTo(r);
        verify(jdbc, times(1)).queryForObject(CHECK, Boolean.class);
    }

    @Test
    @DisplayName("no invoicing tables (V1 Keevo tenant): sales only, checked again next time (the module may come)")
    void tablesAbsent() {
        TenantContext.setCurrentTenant(TENANT);
        when(jdbc.queryForObject(CHECK, Boolean.class)).thenReturn(false);

        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.SALES_ONLY);
        assertThat(source.current().documents()).isEqualTo(RevenueRelations.POS_DOCUMENTS);
        verify(jdbc, times(2)).queryForObject(CHECK, Boolean.class);
    }

    @Test
    @DisplayName("catalogue unreadable: sales only, no exception")
    void unreadable() {
        TenantContext.setCurrentTenant(TENANT);
        when(jdbc.queryForObject(CHECK, Boolean.class)).thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.SALES_ONLY);
    }

    @Test
    @DisplayName("store-scoped relations are sales only and never touch the database")
    void storeScoped() {
        assertThat(source.storeScoped()).isEqualTo(ProvisionedRevenueSource.SALES_ONLY);
        assertThat(source.storeScoped().lines()).doesNotContain("invoice");
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("no valid tenant in context: the check runs on the search_path, nothing is cached")
    void noTenant() {
        when(jdbc.queryForObject(CHECK, Boolean.class)).thenReturn(true);
        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.ALL);
        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.ALL);
        verify(jdbc, times(2)).queryForObject(CHECK, Boolean.class);
    }

    @Test
    @DisplayName("one definition: counted invoices, deducted credit notes (manual ones of bridged invoices included)")
    void predicates() {
        String docs = RevenueRelations.documents(true, true);
        assertThat(docs).contains("s.status = 'COMPLETED'").contains("i.kind = 'INVOICE'")
                .contains("i.status IN ('ISSUED', 'PAID')").contains("i.deleted_at IS NULL")
                .contains("i.superseded_at IS NULL").contains("-cn.total_ttc")
                .contains("(i.source_sale_id IS NULL OR cn.source_event IS NULL)");
        assertThat(RevenueRelations.documents(false, false)).contains("WHERE FALSE").doesNotContain("FROM sales");
        assertThat(RevenueRelations.lines(false, true)).doesNotContain("FROM sale_items");
    }
}
