package com.keevo.invoicing.module;

import com.keevo.invoicing.invoice.adapter.out.persistence.JdbcInvoicePaymentRepository;
import com.keevo.invoicing.invoice.adapter.out.persistence.JdbcInvoiceRepository;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceTerms;
import com.keevo.invoicing.invoice.domain.model.InvoiceTotals;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 20.5 — {@code db/modules/invoicing/V11__invoice_discount_delivery_vat_due.sql} on PostgreSQL, applied to a
 * tenant schema built by V1–V10 that already holds invoices: nothing altered (existing rows read back with the same
 * totals, no discount, no delivery), constraints enforced, re-runnable; the overdue job's UPDATE also marks an ISSUED
 * invoice past its own due date and not fully paid, once — and still runs on a tenant not migrated to V11 yet.
 */
class InvoicingTermsMigrationPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_invoicing_v11";
    private static final String SCHEMA = "kv_term01";
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void database() {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        dataSource = new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password());
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void drop() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static final List<String> SCRIPTS = List.of("V1__invoicing.sql", "V2__invoice_sending.sql",
            "V3__invoice_source_sale.sql", "V4__credit_notes.sql", "V5__sale_invoice_state.sql",
            "V6__invoice_sync_keyset.sql", "V7__invoice_soft_delete.sql", "V8__invoices_kind_quote.sql",
            "V9__invoices_kind_list_index.sql", "V10__invoice_payments.sql");

    private static void script(String name) throws Exception {
        String sql = new ClassPathResource("db/modules/invoicing/" + name).getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL search_path TO \"" + SCHEMA + "\"");
                s.execute(sql);
            }
            c.commit();
        }
    }

    private static UUID insert(String status, String number, boolean pricesIncludeVat) {
        UUID id = UUID.randomUUID();
        boolean issued = !"DRAFT".equals(status);
        jdbc.update("INSERT INTO \"" + SCHEMA + "\".invoices (id, number, status, issue_date, issued_at, paid_at, "
                + "payment_method, client_name, vat_registered, vat_rate, prices_include_vat, total_ht, total_vat, "
                + "total_ttc) VALUES (?, ?, ?, " + (issued ? "current_date, now()" : "NULL, NULL")
                + ", " + ("PAID".equals(status) ? "now(), 'Espèces'" : "NULL, NULL")
                + ", 'Client', true, 19.25, ?, 100000, 19250, 119250)", id, number, status, pricesIncludeVat);
        jdbc.update("INSERT INTO \"" + SCHEMA + "\".invoice_items (id, invoice_id, position, description, quantity, "
                + "unit_price, vat_treatment, vat_rate, total_ht, total_vat, total_ttc) VALUES (?, ?, 1, 'Article', 1, "
                + "?, 'STANDARD', 19.25, 100000, 19250, 119250)", UUID.randomUUID(), id,
                pricesIncludeVat ? 119_250 : 100_000);
        return id;
    }

    @BeforeEach
    void legacyTenant() throws Exception {
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE"); // test database only
        jdbc.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        for (String s : SCRIPTS) {
            script(s);
        }
    }

    @Test
    @DisplayName("V11 on a tenant with invoices: rows untouched, read back with the same totals and no terms; "
            + "re-run is a no-op")
    void upgradesExistingTenant() throws Exception {
        UUID draft = insert("DRAFT", null, true);
        UUID issuedTtc = insert("ISSUED", "FAC-2026-000001", true);
        UUID paidHt = insert("PAID", "FAC-2026-000002", false);
        String select = "SELECT * FROM \"" + SCHEMA + "\".invoices ORDER BY id";
        List<Map<String, Object>> before = jdbc.queryForList(select);

        script("V11__invoice_discount_delivery_vat_due.sql");
        script("V11__invoice_discount_delivery_vat_due.sql"); // idempotent

        List<Map<String, Object>> after = jdbc.queryForList(select);
        for (int i = 0; i < before.size(); i++) {
            Map<String, Object> row = after.get(i);
            before.get(i).forEach((column, value) -> assertThat(row.get(column)).as(column).isEqualTo(value));
            assertThat(row.get("discount_amount")).isEqualTo(0L);
            assertThat(row.get("delivery_fee")).isEqualTo(0L);
            assertThat(row.get("subtotal")).isNull();
            assertThat(row.get("vat_exempt")).isEqualTo(false);
        }
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ?", String.class, SCHEMA))
                .contains("idx_invoices_issued_due_date");
        script("V12__invoices_due_date_index.sql");
        script("V12__invoices_due_date_index.sql"); // idempotent
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ?", String.class, SCHEMA))
                .contains("idx_invoices_issued_due_date_active").doesNotContain("idx_invoices_issued_due_date");
        // Story 20.14 — V16 on the same tenant: rows untouched, no store (no backfill); idempotent
        script("V16__invoice_store.sql");
        script("V16__invoice_store.sql");
        List<Map<String, Object>> withStore = jdbc.queryForList(select);
        for (int i = 0; i < after.size(); i++) {
            Map<String, Object> row = withStore.get(i);
            after.get(i).forEach((column, value) -> assertThat(row.get(column)).as(column).isEqualTo(value));
            assertThat(row.get("store_id")).isNull();
        }

        TenantContext.setCurrentTenant(SCHEMA);
        JdbcInvoiceRepository repository = new JdbcInvoiceRepository(jdbc);
        Invoice ttc = repository.findById(issuedTtc).orElseThrow();
        assertThat(ttc.totals()).isEqualTo(new InvoiceTotals(100_000, 19_250, 119_250, 119_250, 0, 0));
        assertThat(ttc.terms()).isEqualTo(InvoiceTerms.NONE);
        assertThat(ttc.lines().get(0).reference()).isNull();
        assertThat(repository.findById(paidHt).orElseThrow().totals())
                .isEqualTo(new InvoiceTotals(100_000, 19_250, 119_250, 100_000, 0, 0));
        assertThat(repository.findById(draft).orElseThrow().client().rccm()).isNull();
    }

    @Test
    @DisplayName("after V11: discount ≤ subtotal, amounts ≥ 0, own rate within 0–100")
    void constraintsEnforced() throws Exception {
        script("V11__invoice_discount_delivery_vat_due.sql");
        UUID id = insert("DRAFT", null, false);
        String update = "UPDATE \"" + SCHEMA + "\".invoices SET ";
        assertThatThrownBy(() -> jdbc.update(update + "subtotal = 100, discount_amount = 101 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(update + "delivery_fee = -1 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(update + "vat_rate_override = 100.01 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(update + "subtotal = 100, discount_amount = 100, delivery_fee = 50, vat_rate_override = 18 "
                + "WHERE id = ?", id);
    }

    @Test
    @DisplayName("overdue UPDATE: an ISSUED invoice past its own due date and not fully paid → OVERDUE once; due "
            + "today, fully paid or credited are not; a tenant before V11 is skipped")
    void markOverdueByDueDate() throws Exception {
        LocalDate today = LocalDate.parse("2026-11-06");
        TenantContext.setCurrentTenant(SCHEMA);
        InvoicePaymentRepository repository = new JdbcInvoicePaymentRepository(jdbc);
        Instant now = Instant.now().plusSeconds(60);
        insert("ISSUED", "FAC-2026-000009", true);
        // a tenant below V11 is skipped by the job (no invoice due dates)
        assertThat(repository.schemasWithPayments()).doesNotContain(SCHEMA);

        script("V11__invoice_discount_delivery_vat_due.sql");
        script("V12__invoices_due_date_index.sql");
        assertThat(repository.schemasWithPayments()).contains(SCHEMA);
        UUID late = insert("ISSUED", "FAC-2026-000001", true);
        UUID dueToday = insert("ISSUED", "FAC-2026-000002", true);
        UUID paid = insert("ISSUED", "FAC-2026-000003", true);
        UUID partly = insert("ISSUED", "FAC-2026-000004", true);
        UUID credited = insert("ISSUED", "FAC-2026-000005", true);
        String due = "UPDATE \"" + SCHEMA + "\".invoices SET due_date = ?::date WHERE id = ?";
        jdbc.update(due, "2026-11-05", late);
        jdbc.update(due, "2026-11-06", dueToday);
        jdbc.update(due, "2026-11-01", paid);
        jdbc.update(due, "2026-11-01", partly);
        jdbc.update(due, "2026-11-01", credited);
        String payment = "INSERT INTO \"" + SCHEMA + "\".invoice_payments (id, invoice_id, amount, paid_at) "
                + "VALUES (?, ?, ?, now())";
        jdbc.update(payment, UUID.randomUUID(), paid, 119_250);
        jdbc.update(payment, UUID.randomUUID(), partly, 19_250);
        jdbc.update("INSERT INTO \"" + SCHEMA + "\".credit_notes (id, number, invoice_id, reason, total_ht, "
                + "total_vat, total_ttc, issued_at) VALUES (?, 'AV-2026-000001', ?, 'Retour', 100000, 19250, 119250, "
                + "now())", UUID.randomUUID(), credited);

        assertThat(repository.markOverdue(today, now)).containsExactly("FAC-2026-000001", "FAC-2026-000004");
        assertThat(repository.markOverdue(today, now)).isEmpty();
        assertThat(jdbc.queryForList("SELECT payment_state FROM \"" + SCHEMA + "\".invoices WHERE id IN (?, ?, ?)",
                String.class, dueToday, paid, credited)).containsOnlyNulls();
    }
}
