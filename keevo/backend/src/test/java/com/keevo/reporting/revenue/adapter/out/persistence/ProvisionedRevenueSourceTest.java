package com.keevo.reporting.revenue.adapter.out.persistence;

import com.keevo.shared.capability.revenue.RevenueQueryPort.Relations;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 20.9 — the revenue source's branches follow the tables present in the tenant schema (their data exists),
 * never the module's current activation; it never provisions nor fails. Story 20.14 — the documents' store follows
 * the presence of {@code invoices.store_id} (invoicing V16): then store-scoped figures include documents.
 */
class ProvisionedRevenueSourceTest {

    private static final String TENANT = "kv_abc123";
    private static final String CHECK = ProvisionedRevenueSource.INVOICING_LEVEL;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private Instant now = Instant.parse("2026-11-08T10:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final ProvisionedRevenueSource source = new ProvisionedRevenueSource(jdbc, clock);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("invoicing V16: sales, invoices and credit notes with their store, also store-scoped; found once, "
            + "then cached per tenant")
    void tablesWithStores() {
        TenantContext.setCurrentTenant(TENANT);
        when(jdbc.queryForObject(CHECK, Integer.class)).thenReturn(ProvisionedRevenueSource.DOCUMENTS_WITH_STORES);

        Relations r = source.current();
        assertThat(r.documents()).contains("FROM sales s").contains("FROM invoices i").contains("FROM credit_notes cn")
                .contains("i.store_id");
        assertThat(r.lines()).contains("FROM sale_items si").contains("FROM invoice_items it")
                .contains("FROM credit_note_items cni").contains("i.store_id");
        assertThat(source.current()).isEqualTo(r);
        assertThat(source.storeScoped()).isEqualTo(r);
        verify(jdbc, times(1)).queryForObject(CHECK, Integer.class);
    }

    @Test
    @DisplayName("invoicing V11–V15: documents without store in the all-stores figures, store-scoped sales only; "
            + "cached for the TTL, then checked again (V16 may come) — and once V16 is seen, for good")
    void tablesWithoutStores() {
        TenantContext.setCurrentTenant(TENANT);
        when(jdbc.queryForObject(CHECK, Integer.class)).thenReturn(ProvisionedRevenueSource.DOCUMENTS);

        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.ALL);
        assertThat(source.current().documents()).doesNotContain("i.store_id").contains("FROM invoices i");
        assertThat(source.storeScoped()).isEqualTo(ProvisionedRevenueSource.SALES_ONLY);
        verify(jdbc, times(1)).queryForObject(CHECK, Integer.class);

        now = now.plus(ProvisionedRevenueSource.LOWER_LEVEL_TTL);
        when(jdbc.queryForObject(CHECK, Integer.class)).thenReturn(ProvisionedRevenueSource.DOCUMENTS_WITH_STORES);
        assertThat(source.storeScoped()).isEqualTo(ProvisionedRevenueSource.ALL_WITH_STORES);
        now = now.plus(ProvisionedRevenueSource.LOWER_LEVEL_TTL.multipliedBy(10));
        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.ALL_WITH_STORES);
        verify(jdbc, times(2)).queryForObject(CHECK, Integer.class);
    }

    @Test
    @DisplayName("no invoicing tables (V1 Keevo tenant): sales only, cached for the TTL then checked again")
    void tablesAbsent() {
        TenantContext.setCurrentTenant(TENANT);
        when(jdbc.queryForObject(CHECK, Integer.class)).thenReturn(ProvisionedRevenueSource.SALES);

        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.SALES_ONLY);
        assertThat(source.current().documents()).isEqualTo(RevenueRelations.POS_DOCUMENTS);
        assertThat(source.storeScoped()).isEqualTo(ProvisionedRevenueSource.SALES_ONLY);
        verify(jdbc, times(1)).queryForObject(CHECK, Integer.class);
        now = now.plus(ProvisionedRevenueSource.LOWER_LEVEL_TTL);
        when(jdbc.queryForObject(CHECK, Integer.class)).thenReturn(ProvisionedRevenueSource.DOCUMENTS);
        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.ALL);
        verify(jdbc, times(2)).queryForObject(CHECK, Integer.class);
    }

    @Test
    @DisplayName("the level only goes up: a lower answer after the TTL (catalogue glitch) keeps the level seen")
    void neverDown() {
        TenantContext.setCurrentTenant(TENANT);
        when(jdbc.queryForObject(CHECK, Integer.class)).thenReturn(ProvisionedRevenueSource.DOCUMENTS);
        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.ALL);
        now = now.plus(ProvisionedRevenueSource.LOWER_LEVEL_TTL);
        when(jdbc.queryForObject(CHECK, Integer.class)).thenReturn(ProvisionedRevenueSource.SALES);
        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.ALL);
    }

    @Test
    @DisplayName("catalogue unreadable: sales only, no exception")
    void unreadable() {
        TenantContext.setCurrentTenant(TENANT);
        when(jdbc.queryForObject(CHECK, Integer.class)).thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.SALES_ONLY);
        assertThat(source.storeScoped()).isEqualTo(ProvisionedRevenueSource.SALES_ONLY);
    }

    @Test
    @DisplayName("20.11 — taxed relations: the sales carry the bridge invoice's VAT or the seller's rate; the 20.9 "
            + "documents relation has no join; store-scoped below V16 = taxed sales only, from V16 = the full taxed "
            + "relations with the documents' store; one cached lookup")
    void taxedRelations() {
        TenantContext.setCurrentTenant(TENANT);
        when(jdbc.queryForObject(CHECK, Integer.class)).thenReturn(ProvisionedRevenueSource.DOCUMENTS);

        Relations all = source.current();
        assertThat(all.taxedDocuments()).contains("LEFT JOIN invoices bi ON bi.source_sale_id = s.id")
                .contains("LEFT JOIN invoicing_settings st").contains("FROM invoices i").contains("FROM credit_notes cn");
        assertThat(all.documents()).doesNotContain("LEFT JOIN").contains("0::bigint AS tax_amount");
        Relations store = source.storeScopedTaxed();
        assertThat(store.documents()).isEqualTo(RevenueRelations.POS_DOCUMENTS);
        assertThat(store.taxedDocuments()).contains("LEFT JOIN invoices bi").doesNotContain("FROM invoices i ")
                .doesNotContain("credit_notes");
        verify(jdbc, times(1)).queryForObject(CHECK, Integer.class);

        TenantContext.setCurrentTenant("kv_abc124");
        when(jdbc.queryForObject(CHECK, Integer.class)).thenReturn(ProvisionedRevenueSource.DOCUMENTS_WITH_STORES);
        assertThat(source.storeScopedTaxed()).isEqualTo(ProvisionedRevenueSource.ALL_WITH_STORES);
        assertThat(source.storeScopedTaxed().taxedDocuments()).contains("LEFT JOIN invoices bi").contains("i.store_id");
    }

    @Test
    @DisplayName("20.11 — without invoicing tables: sales-only relations everywhere, taxed ones included (no join)")
    void taxedRelationsWithoutInvoicing() {
        TenantContext.setCurrentTenant(TENANT);
        when(jdbc.queryForObject(CHECK, Integer.class)).thenReturn(ProvisionedRevenueSource.SALES);
        assertThat(source.current().taxedDocuments()).isEqualTo(RevenueRelations.POS_DOCUMENTS);
        assertThat(source.storeScopedTaxed()).isEqualTo(ProvisionedRevenueSource.SALES_ONLY);
        assertThat(source.storeScopedTaxed().taxedDocuments()).doesNotContain("invoice");
    }

    @Test
    @DisplayName("no valid tenant in context: the check runs on the search_path, nothing is cached")
    void noTenant() {
        when(jdbc.queryForObject(CHECK, Integer.class)).thenReturn(ProvisionedRevenueSource.DOCUMENTS_WITH_STORES);
        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.ALL_WITH_STORES);
        assertThat(source.current()).isEqualTo(ProvisionedRevenueSource.ALL_WITH_STORES);
        verify(jdbc, times(2)).queryForObject(CHECK, Integer.class);
    }

    @Test
    @DisplayName("one definition: counted invoices, deducted credit notes (manual ones of bridged invoices included); "
            + "client and transaction instant on every branch")
    void predicates() {
        String docs = RevenueRelations.documents(true, true);
        assertThat(docs).contains("s.status = 'COMPLETED'").contains("i.kind = 'INVOICE'")
                .contains("i.status IN ('ISSUED', 'PAID')").contains("i.deleted_at IS NULL")
                .contains("i.superseded_at IS NULL").contains("-cn.total_ttc")
                .contains("(i.source_sale_id IS NULL OR cn.source_event IS NULL)")
                .contains("s.client_id AS client_id").contains("s.occurred_at AS recorded_at")
                .contains("i.client_id, i.issued_at").contains("i.client_id, cn.issued_at");
        assertThat(RevenueRelations.documents(false, false)).contains("WHERE FALSE").doesNotContain("FROM sales")
                .contains("recorded_at");
        assertThat(RevenueRelations.lines(false, true)).doesNotContain("FROM sale_items");
        assertThat(RevenueRelations.documents(true, true, false)).doesNotContain("i.store_id");
    }
}
