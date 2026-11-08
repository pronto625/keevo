package com.keevo.admin.dashboard.adapter.out.persistence;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.ActivityItem;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.ActivityType;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.DocumentFigures;
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
 * Story 20.13 — the platform dashboard reader on PostgreSQL: document figures by payment state (stored or legacy NULL
 * state, the {@code idx_invoices_payment_state_list} expression), open quotes, drafts and POS-bridged invoices left
 * out; invoices by civil issue month inside / outside a window; the activity feed with its French details (received
 * payments included, decision A6).
 */
@DisplayName("20.13 — platform dashboard reader (documents, invoices by month, activity) on PostgreSQL")
class PlatformDashboardReaderPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_platform_dashboard";
    private static final String SCHEMA = "kv_pd13aa";
    private static final Instant T0 = Instant.parse("2026-11-06T09:00:00Z");

    private static DriverManagerDataSource admin;
    private static JdbcTemplate jdbc;
    private static JdbcPlatformDashboardReader reader;
    private static List<TenantBranch> tenants;
    private static int numbers;

    @BeforeAll
    static void seed() throws Exception {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        admin = new DriverManagerDataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password());
        jdbc = new JdbcTemplate(admin);
        jdbc.execute("CREATE TABLE public.tenants (id UUID PRIMARY KEY, name VARCHAR(100), designation VARCHAR(150), "
                + "schema_name VARCHAR(15), status VARCHAR(20), created_at TIMESTAMPTZ DEFAULT now())");
        jdbc.execute("CREATE TABLE public.users (id UUID PRIMARY KEY, first_name VARCHAR(100), last_name VARCHAR(100), "
                + "role VARCHAR(20), is_active BOOLEAN, created_at TIMESTAMPTZ)");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        TenantFoundation.upgrade(admin, SCHEMA);
        for (String script : List.of("V1__invoicing.sql", "V2__invoice_sending.sql", "V3__invoice_source_sale.sql",
                "V4__credit_notes.sql", "V5__sale_invoice_state.sql", "V6__invoice_sync_keyset.sql",
                "V7__invoice_soft_delete.sql", "V8__invoices_kind_quote.sql", "V9__invoices_kind_list_index.sql",
                "V10__invoice_payments.sql", "V11__invoice_discount_delivery_vat_due.sql",
                "V12__invoices_due_date_index.sql", "V13__invoicing_settings_derive_from_documents.sql",
                "V14__revenue_indexes.sql", "V17__invoice_payments_paid_at_index.sql",
                "V17__invoice_payments_paid_at_index.sql")) {
            script(script); // V17 twice: idempotent
        }
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO public.tenants (id, name, schema_name, status, created_at) VALUES (?, 'Boutique Doc', ?, "
                + "'ACTIVE', ?)", tenant, SCHEMA, Timestamp.from(T0.minusSeconds(2 * 86_400)));
        jdbc.update("INSERT INTO public.users VALUES (?, 'Awa', 'Ngo', 'OWNER', true, ?)", UUID.randomUUID(),
                Timestamp.from(T0.minusSeconds(86_400)));
        Timestamp old = Timestamp.from(Instant.parse("2025-01-01T00:00:00Z"));
        UUID store = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, ?, ?, true, 'Akwa', 'STORE')", store, old, old);

        invoice("ISSUED", "UNPAID", LocalDate.of(2026, 11, 3), 1_000, null, 1);
        UUID paid = invoice("PAID", "PAID", LocalDate.of(2026, 11, 4), 2_000, null, 2);
        invoice("ISSUED", "OVERDUE", LocalDate.of(2026, 10, 10), 3_000, null, 3);
        invoice("ISSUED", "PARTIAL", LocalDate.of(2026, 11, 5), 4_000, null, 4);
        invoice("ISSUED", null, LocalDate.of(2026, 11, 6), 5_000, null, 5);   // legacy: ISSUED → UNPAID
        invoice("PAID", null, LocalDate.of(2026, 11, 6), 6_000, null, 6);     // legacy: PAID → PAID
        invoice("DRAFT", null, null, 7_000, null, 40);                         // never counted
        UUID sale = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".sales (id, created_at, employee_id, occurred_at, payment_mode, status, "
                + "store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, 8000, 0)",
                sale, Timestamp.from(T0), UUID.randomUUID(), Timestamp.from(T0), store);
        invoice("ISSUED", "PAID", LocalDate.of(2026, 11, 6), 8_000, sale, 50);  // POS bridge: counted as the sale
        quote("DRAFT", 900, 20);
        quote("CANCELLED", 999, 45);
        jdbc.update("INSERT INTO " + SCHEMA + ".invoice_payments (id, invoice_id, amount, paid_at) VALUES (?, ?, 2500, ?)",
                UUID.randomUUID(), paid, Timestamp.from(T0.plusSeconds(30 * 60)));

        CrossTenantSql crossTenant = new CrossTenantSql(jdbc, 100);
        reader = new JdbcPlatformDashboardReader(jdbc, crossTenant);
        tenants = reader.liveTenants();
    }

    @AfterAll
    static void drop() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @Test
    @DisplayName("document figures: counted invoices by payment state (NULL = legacy rule), open quotes only")
    void documentFigures() {
        DocumentFigures d = reader.documentFigures(tenants);

        assertThat(d).isEqualTo(new DocumentFigures(6, 2, 1, 2, 1, 1));
    }

    @Test
    @DisplayName("invoices by civil issue month: inside the window counted, outside not")
    void invoicesByMonth() {
        Instant nov = Instant.parse("2026-10-31T23:00:00Z");
        Instant dec = Instant.parse("2026-11-30T23:00:00Z");

        assertThat(reader.invoicesByMonth(tenants, nov, dec)).isEqualTo(Map.of("2026-11", 5L));
        assertThat(reader.invoicesByMonth(tenants, Instant.parse("2026-09-30T23:00:00Z"), nov))
                .isEqualTo(Map.of("2026-10", 1L));
    }

    @Test
    @DisplayName("activity: payment, open quote, counted invoices with French details; drafts / bridged / cancelled out")
    void activity() {
        List<ActivityItem> items = reader.activity(tenants, 4);

        assertThat(items).extracting(ActivityItem::type)
                .containsExactly("PAYMENT_RECEIVED", "QUOTE_CREATED", "INVOICE_CREATED", "INVOICE_CREATED");
        assertThat(items).extracting(ActivityItem::detail).containsExactly("Paiement de 2 500 FCFA",
                "Devis de 900 FCFA", "Facture de 6 000 FCFA", "Facture de 5 000 FCFA");
        assertThat(items.get(0).label()).isEqualTo("Paiement reçu");
        assertThat(items.get(0).tenantName()).isEqualTo("Boutique Doc");

        List<ActivityItem> all = reader.activity(tenants, 50);
        assertThat(all).extracting(ActivityItem::type).contains("STORE_CREATED", "ACCOUNT_CREATED", "USER_CREATED");
        assertThat(all).filteredOn(i -> i.type().equals("INVOICE_CREATED")).hasSize(6);
        assertThat(all).filteredOn(i -> i.type().equals("QUOTE_CREATED")).hasSize(1);
        assertThat(all).filteredOn(i -> i.type().equals("USER_CREATED")).extracting(ActivityItem::detail)
                .containsExactly("Awa Ngo");
    }

    @Test
    @DisplayName("an event without amount shows its label alone")
    void detailWithoutAmount() {
        assertThat(JdbcPlatformDashboardReader.detail(ActivityType.INVOICE_CREATED, null, null))
                .isEqualTo("Facture créée");
        assertThat(JdbcPlatformDashboardReader.detail(ActivityType.PAYMENT_RECEIVED, null, null))
                .isEqualTo("Paiement reçu");
        assertThat(JdbcPlatformDashboardReader.detail(ActivityType.STORE_CREATED, "Akwa", null)).isEqualTo("Akwa");
    }

    // ── seed helpers (test database only) ────────────────────────────────────

    private static void script(String name) throws Exception {
        String sql = new ClassPathResource("db/modules/invoicing/" + name).getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = admin.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL search_path TO \"" + SCHEMA + "\"");
                s.execute(sql);
            }
            c.commit();
        }
    }

    private static UUID invoice(String status, String paymentState, LocalDate issueDate, long total, UUID sourceSale,
                                int minute) {
        UUID id = UUID.randomUUID();
        boolean issued = !"DRAFT".equals(status);
        Timestamp at = Timestamp.from(T0.plusSeconds(minute * 60L));
        jdbc.update("INSERT INTO " + SCHEMA + ".invoices (id, number, status, issue_date, issued_at, paid_at, "
                        + "payment_method, client_name, vat_registered, vat_rate, prices_include_vat, total_ht, total_vat, "
                        + "total_ttc, source_sale_id, created_by, kind, payment_state, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'Client', false, 0, true, ?, 0, ?, ?, ?, 'INVOICE', ?, ?)",
                id, issued ? "FAC-2026-" + String.format("%06d", ++numbers) : null, status,
                issued ? Date.valueOf(issueDate) : null, issued ? at : null, "PAID".equals(status) ? at : null,
                "PAID".equals(status) ? "Espèces" : null, total, total, sourceSale, UUID.randomUUID(), paymentState, at);
        return id;
    }

    private static void quote(String status, long total, int minute) {
        Timestamp at = Timestamp.from(T0.plusSeconds(minute * 60L));
        boolean cancelled = "CANCELLED".equals(status);
        jdbc.update("INSERT INTO " + SCHEMA + ".invoices (id, status, client_name, vat_registered, vat_rate, "
                        + "prices_include_vat, total_ht, total_vat, total_ttc, created_by, kind, cancelled_at, "
                        + "cancellation_reason, created_at) VALUES (?, ?, 'Client', false, 0, true, ?, 0, ?, ?, 'QUOTE', "
                        + "?, ?, ?)",
                UUID.randomUUID(), status, total, total, UUID.randomUUID(), cancelled ? at : null,
                cancelled ? "Erreur" : null, at);
    }
}
