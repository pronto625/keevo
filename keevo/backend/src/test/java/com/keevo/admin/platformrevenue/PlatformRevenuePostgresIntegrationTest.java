package com.keevo.admin.platformrevenue;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.platformrevenue.adapter.out.RevenueSourcePlatformRevenueAdapter;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort.Bucket;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort.Totals;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantFoundation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.13 (R4) — the platform revenue adapter on PostgreSQL: three tenant schemas in one statement — POS with a
 * sale bridged to an automatic invoice (counted once), documents only (invoice − credit note, quote / draft not
 * counted), POS only without the invoicing tables (no branch failure) — plus a deleted tenant never read and a
 * schema-less tenant skipped by the catalogue filter.
 */
@DisplayName("20.13 — platform revenue across tenant schemas (single statement, no double count)")
class PlatformRevenuePostgresIntegrationTest {

    private static final String TEST_DB = "keevo_platform_revenue";
    private static final String POS_AND_DOCS = "kv_pr13aa";
    private static final String DOCS_ONLY = "kv_pr13bb";
    private static final String POS_ONLY = "kv_pr13cc";
    private static final Instant NOV_5 = Instant.parse("2026-11-05T09:00:00Z");
    private static final Instant OCT_20 = Instant.parse("2026-10-20T09:00:00Z");

    private static DriverManagerDataSource admin;
    private static JdbcTemplate jdbc;
    private static RevenueSourcePlatformRevenueAdapter adapter;
    private static List<TenantBranch> tenants;

    @BeforeAll
    static void seed() throws Exception {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        admin = new DriverManagerDataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password());
        jdbc = new JdbcTemplate(admin);
        jdbc.execute("CREATE TABLE public.tenants (id UUID PRIMARY KEY, name VARCHAR(100), designation VARCHAR(150), "
                + "schema_name VARCHAR(15), status VARCHAR(20), created_at TIMESTAMPTZ DEFAULT now())");
        for (String schema : List.of(POS_AND_DOCS, DOCS_ONLY, POS_ONLY)) {
            jdbc.execute("CREATE SCHEMA \"" + schema + "\"");
            TenantFoundation.upgrade(admin, schema);
        }
        for (String schema : List.of(POS_AND_DOCS, DOCS_ONLY)) {
            invoicing(schema);
        }
        UUID a = tenant("Alpha", POS_AND_DOCS, "ACTIVE");
        UUID b = tenant("Beta", DOCS_ONLY, "ACTIVE");
        UUID c = tenant("Gamma", POS_ONLY, "SUSPENDED");
        tenant("Gone", "kv_pr13dd", "DELETED");
        UUID ghost = tenant("Ghost", "kv_pr13ee", "ACTIVE"); // schema never created

        // Alpha: a POS sale of 5 000 bridged to its automatic invoice (counts once, as the sale), a cancelled sale.
        UUID store = store(POS_AND_DOCS);
        UUID bridged = sale(POS_AND_DOCS, store, 5_000, "COMPLETED", NOV_5);
        sale(POS_AND_DOCS, store, 9_000, "CANCELLED", NOV_5);
        invoice(POS_AND_DOCS, "ISSUED", LocalDate.of(2026, 11, 5), 5_000, bridged, "INVOICE");
        // Beta: invoice 7 000 − credit note 2 000; a quote and a draft never count; an October invoice.
        UUID inv = invoice(DOCS_ONLY, "ISSUED", LocalDate.of(2026, 11, 5), 7_000, null, "INVOICE");
        creditNote(DOCS_ONLY, inv, 2_000, NOV_5);
        invoice(DOCS_ONLY, "DRAFT", LocalDate.of(2026, 11, 5), 4_000, null, "INVOICE");
        invoice(DOCS_ONLY, "DRAFT", null, 9_000, null, "QUOTE");
        invoice(DOCS_ONLY, "ISSUED", LocalDate.of(2026, 10, 20), 3_000, null, "INVOICE");
        // Gamma: POS only, one sale in November, one in October.
        UUID storeC = store(POS_ONLY);
        sale(POS_ONLY, storeC, 1_000, "COMPLETED", NOV_5);
        sale(POS_ONLY, storeC, 600, "COMPLETED", OCT_20);

        CrossTenantSql crossTenant = new CrossTenantSql(jdbc, 1000);
        adapter = new RevenueSourcePlatformRevenueAdapter(jdbc, crossTenant);
        tenants = List.of(branch(a, POS_AND_DOCS), branch(b, DOCS_ONLY), branch(c, POS_ONLY), branch(ghost, "kv_pr13ee"));
    }

    @AfterAll
    static void drop() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @Test
    @DisplayName("per tenant, all time: bridged sale once, credit note deducted, quote / draft / cancelled excluded")
    void perTenantAllTime() {
        Map<String, Totals> totals = adapter.totals(tenants, null, null, Bucket.TENANT);

        assertThat(totals.get(tenants.get(0).id())).isEqualTo(new Totals(5_000, 1));
        assertThat(totals.get(tenants.get(1).id())).isEqualTo(new Totals(8_000, 2)); // 7 000 − 2 000 + 3 000
        assertThat(totals.get(tenants.get(2).id())).isEqualTo(new Totals(1_600, 2));
        assertThat(totals).doesNotContainKey(tenants.get(3).id()); // no schema: not a branch, no failure
    }

    @Test
    @DisplayName("by WAT month and day within a window")
    void byMonthAndDay() {
        Instant from = Instant.parse("2026-09-30T23:00:00Z");
        Instant to = Instant.parse("2026-11-30T23:00:00Z");

        Map<String, Totals> months = adapter.totals(tenants, from, to, Bucket.MONTH);
        assertThat(months.get("2026-11")).isEqualTo(new Totals(11_000, 3)); // 5 000 + 5 000 + 1 000
        assertThat(months.get("2026-10")).isEqualTo(new Totals(3_600, 2));

        Map<String, Totals> days = adapter.totals(tenants, Instant.parse("2026-11-04T23:00:00Z"),
                Instant.parse("2026-11-05T23:00:00Z"), Bucket.DAY);
        assertThat(days).containsOnlyKeys("2026-11-05");
        assertThat(days.get("2026-11-05").revenue()).isEqualTo(11_000);
    }

    @Test
    @DisplayName("only the tenants asked for are read; none → empty")
    void subsetOnly() {
        assertThat(adapter.totals(List.of(tenants.get(2)), null, null, Bucket.TENANT))
                .containsOnlyKeys(tenants.get(2).id());
        assertThat(adapter.totals(List.of(), null, null, Bucket.TENANT)).isEmpty();
    }

    // ── seed helpers (test database only) ────────────────────────────────────

    private static void invoicing(String schema) throws Exception {
        for (String script : List.of("V1__invoicing.sql", "V2__invoice_sending.sql", "V3__invoice_source_sale.sql",
                "V4__credit_notes.sql", "V5__sale_invoice_state.sql", "V6__invoice_sync_keyset.sql",
                "V7__invoice_soft_delete.sql", "V8__invoices_kind_quote.sql", "V9__invoices_kind_list_index.sql",
                "V10__invoice_payments.sql", "V11__invoice_discount_delivery_vat_due.sql",
                "V12__invoices_due_date_index.sql", "V13__invoicing_settings_derive_from_documents.sql",
                "V14__revenue_indexes.sql")) {
            String sql = new ClassPathResource("db/modules/invoicing/" + script).getContentAsString(StandardCharsets.UTF_8);
            try (Connection conn = admin.getConnection()) {
                conn.setAutoCommit(false);
                try (Statement s = conn.createStatement()) {
                    s.execute("SET LOCAL search_path TO \"" + schema + "\"");
                    s.execute(sql);
                }
                conn.commit();
            }
        }
    }

    private static UUID tenant(String name, String schema, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.tenants (id, name, schema_name, status) VALUES (?, ?, ?, ?)",
                id, name, schema, status);
        return id;
    }

    private static TenantBranch branch(UUID id, String schema) {
        return new TenantBranch(id.toString(), schema, schema, "ACTIVE", null);
    }

    private static UUID store(String schema) {
        UUID id = UUID.randomUUID();
        Timestamp t = Timestamp.from(Instant.parse("2025-01-01T00:00:00Z"));
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, ?, ?, true, 'Boutique', 'STORE')", id, t, t);
        return id;
    }

    private static UUID sale(String schema, UUID store, int total, String status, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".sales (id, created_at, employee_id, occurred_at, payment_mode, "
                        + "status, store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', ?, ?, ?, 0)",
                id, Timestamp.from(at), UUID.randomUUID(), Timestamp.from(at), status, store, total);
        return id;
    }

    private static int numbers;

    private static UUID invoice(String schema, String status, LocalDate issueDate, long total, UUID sourceSale,
                                String kind) {
        UUID id = UUID.randomUUID();
        boolean issued = issueDate != null && !"DRAFT".equals(status);
        Timestamp issuedAt = issued ? Timestamp.from(NOV_5) : null;
        jdbc.update("INSERT INTO \"" + schema + "\".invoices (id, number, status, issue_date, issued_at, client_name, "
                        + "vat_registered, vat_rate, prices_include_vat, total_ht, total_vat, total_ttc, source_sale_id, "
                        + "created_by, kind) VALUES (?, ?, ?, ?, ?, 'Client', false, 0, true, ?, 0, ?, ?, ?, ?)",
                id, issued ? "FAC-2026-" + String.format("%06d", ++numbers) : null, status,
                issued ? Date.valueOf(issueDate) : null, issuedAt, total, total, sourceSale,
                UUID.randomUUID(), kind);
        return id;
    }

    private static void creditNote(String schema, UUID invoice, long total, Instant at) {
        jdbc.update("INSERT INTO \"" + schema + "\".credit_notes (id, number, invoice_id, reason, total_ht, total_vat, "
                        + "total_ttc, issued_at) VALUES (?, ?, ?, 'Retour', ?, 0, ?, ?)",
                UUID.randomUUID(), "AV-2026-" + String.format("%06d", ++numbers), invoice, total, total,
                Timestamp.from(at));
    }
}
