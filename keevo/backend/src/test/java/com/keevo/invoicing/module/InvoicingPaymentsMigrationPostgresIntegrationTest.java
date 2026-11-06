package com.keevo.invoicing.module;

import com.keevo.invoicing.invoice.adapter.out.persistence.JdbcInvoicePaymentRepository;
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
 * Story 20.4 — {@code db/modules/invoicing/V10__invoice_payments.sql} on PostgreSQL, applied to a tenant schema built by
 * V1–V9 that already holds DRAFT / ISSUED / PAID / CANCELLED invoices: nothing altered (payment_state NULL, no row
 * written), table and indexes created, row constraints enforced, re-runnable; the overdue job's UPDATE marks an
 * invoice once and only an ISSUED invoice with an unpaid instalment past due.
 */
class InvoicingPaymentsMigrationPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_invoicing_v10";
    private static final String SCHEMA = "kv_paym01";
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
            "V9__invoices_kind_list_index.sql");

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

    private static UUID insert(String status, String number) {
        UUID id = UUID.randomUUID();
        boolean issued = !"DRAFT".equals(status);
        jdbc.update("INSERT INTO \"" + SCHEMA + "\".invoices (id, number, status, issue_date, issued_at, paid_at, "
                + "payment_method, cancelled_at, cancellation_reason, client_name, vat_registered, vat_rate, "
                + "prices_include_vat, total_ttc) VALUES (?, ?, ?, " + (issued ? "current_date, now()" : "NULL, NULL")
                + ", " + ("PAID".equals(status) ? "now(), 'Espèces'" : "NULL, NULL") + ", "
                + ("CANCELLED".equals(status) ? "now(), 'motif'" : "NULL, NULL")
                + ", 'Client', false, 19.25, true, 100000)", id, number, status);
        return id;
    }

    private static void payment(UUID invoice, long amount, String dueDate, boolean paid) {
        jdbc.update("INSERT INTO \"" + SCHEMA + "\".invoice_payments (id, invoice_id, amount, due_date, paid_at) "
                + "VALUES (?, ?, ?, ?::date, " + (paid ? "now()" : "NULL") + ")", UUID.randomUUID(), invoice, amount,
                dueDate);
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
    @DisplayName("V10 on a tenant with DRAFT/ISSUED/PAID/CANCELLED invoices: rows untouched, state NULL, no payment "
            + "row, indexes created; re-run is a no-op")
    void upgradesExistingTenant() throws Exception {
        insert("DRAFT", null);
        insert("ISSUED", "FAC-2026-000001");
        insert("PAID", "FAC-2026-000002");
        insert("CANCELLED", "FAC-2026-000003");
        String select = "SELECT id, number, status, paid_at, payment_method, updated_at FROM \"" + SCHEMA
                + "\".invoices ORDER BY id";
        List<Map<String, Object>> before = jdbc.queryForList(select);

        script("V10__invoice_payments.sql");
        script("V10__invoice_payments.sql"); // idempotent

        assertThat(jdbc.queryForList(select)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + SCHEMA + "\".invoices WHERE payment_state IS NOT "
                + "NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + SCHEMA + "\".invoice_payments", Integer.class))
                .isZero();
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ?", String.class, SCHEMA))
                .contains("idx_invoice_payments_invoice", "idx_invoice_payments_unpaid_due",
                        "idx_invoices_payment_state_list");
        assertThat(jdbc.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = '\"" + SCHEMA
                + "\".invoices'::regclass AND conname = 'invoices_payment_state_check'", String.class)).hasSize(1);
    }

    @Test
    @DisplayName("after V10: amount > 0, a row is dated, a known state only, rows follow their invoice")
    void constraintsEnforced() throws Exception {
        script("V10__invoice_payments.sql");
        UUID invoice = insert("ISSUED", "FAC-2026-000001");
        assertThatThrownBy(() -> payment(invoice, 0, "2026-12-01", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> payment(invoice, 100, null, false)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> payment(UUID.randomUUID(), 100, "2026-12-01", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE \"" + SCHEMA + "\".invoices SET payment_state = 'LATE'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        payment(invoice, 100, "2026-12-01", false);
        payment(invoice, 100, null, true);
    }

    @Test
    @DisplayName("overdue UPDATE: only active ISSUED invoices with an unpaid instalment past due, never a stored PAID, once; "
            + "numbers returned")
    void markOverdue() throws Exception {
        script("V10__invoice_payments.sql");
        LocalDate today = LocalDate.parse("2026-11-06");
        UUID late = insert("ISSUED", "FAC-2026-000001");
        payment(late, 50_000, "2026-11-05", false);
        payment(late, 50_000, "2026-11-20", false);
        UUID dueToday = insert("ISSUED", "FAC-2026-000002");
        payment(dueToday, 50_000, "2026-11-06", false);
        UUID settled = insert("ISSUED", "FAC-2026-000003");
        payment(settled, 50_000, "2026-11-01", true);
        UUID cancelled = insert("CANCELLED", "FAC-2026-000004");
        payment(cancelled, 50_000, "2026-11-01", false);
        UUID lateToo = insert("ISSUED", "FAC-2026-000005");
        payment(lateToo, 10_000, "2026-10-01", false);
        UUID superseded = insert("ISSUED", "FAC-2026-000006"); // replaced by a corrected sale's invoice
        payment(superseded, 10_000, "2026-10-01", false);
        jdbc.update("UPDATE \"" + SCHEMA + "\".invoices SET superseded_at = now() WHERE id = ?", superseded);
        UUID storedPaid = insert("ISSUED", "FAC-2026-000007"); // fully covered by a credit note: stored PAID
        payment(storedPaid, 10_000, "2026-10-01", false);
        jdbc.update("UPDATE \"" + SCHEMA + "\".invoices SET payment_state = 'PAID' WHERE id = ?", storedPaid);
        Instant before = jdbc.queryForObject("SELECT updated_at FROM \"" + SCHEMA + "\".invoices WHERE id = ?",
                java.sql.Timestamp.class, late).toInstant();

        // Story 20.5 — the job's statement needs the invoice due dates (V11, index V12)
        script("V11__invoice_discount_delivery_vat_due.sql");
        script("V12__invoices_due_date_index.sql");
        TenantContext.setCurrentTenant(SCHEMA);
        InvoicePaymentRepository repository = new JdbcInvoicePaymentRepository(jdbc);
        Instant now = Instant.now().plusSeconds(60);
        assertThat(repository.schemasWithPayments()).contains(SCHEMA);
        assertThat(repository.markOverdue(today, now)).containsExactly("FAC-2026-000001", "FAC-2026-000005");
        assertThat(repository.markOverdue(today, now)).isEmpty(); // already OVERDUE: a later run finds nothing new

        assertThat(jdbc.queryForObject("SELECT payment_state FROM \"" + SCHEMA + "\".invoices WHERE id = ?",
                String.class, late)).isEqualTo("OVERDUE");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM \"" + SCHEMA + "\".invoices WHERE id = ?",
                java.sql.Timestamp.class, late).toInstant()).isAfter(before);
        assertThat(jdbc.queryForList("SELECT payment_state FROM \"" + SCHEMA + "\".invoices WHERE id IN (?, ?, ?, ?)",
                String.class, dueToday, settled, cancelled, superseded)).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT payment_state FROM \"" + SCHEMA + "\".invoices WHERE id = ?",
                String.class, storedPaid)).isEqualTo("PAID");
    }
}
