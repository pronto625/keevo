package com.keevo.invoicing.module;

import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import org.junit.jupiter.api.AfterAll;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 20.3 — {@code db/modules/invoicing/V8__invoices_kind_quote.sql} on PostgreSQL, applied to a tenant schema
 * built by V1–V7 that already holds DRAFT / ISSUED / PAID / CANCELLED invoices (the shape of every existing tenant):
 * existing rows untouched and of kind INVOICE, the V1 row rules still enforced for invoices, quotes accepted only
 * without number, CONVERTED only for a linked quote, one invoice per source quote, the script re-runnable.
 */
class InvoicingQuoteMigrationPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_invoicing_v8";
    private static final String SCHEMA = "kv_quote01";
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

    /** Runs a module script in the tenant schema, in one transaction (as the module migrations do). */
    private static void script(String version) throws Exception {
        String name = switch (version) {
            case "V1" -> "V1__invoicing.sql";
            case "V2" -> "V2__invoice_sending.sql";
            case "V3" -> "V3__invoice_source_sale.sql";
            case "V4" -> "V4__credit_notes.sql";
            case "V5" -> "V5__sale_invoice_state.sql";
            case "V6" -> "V6__invoice_sync_keyset.sql";
            case "V7" -> "V7__invoice_soft_delete.sql";
            case "V9" -> "V9__invoices_kind_list_index.sql";
            default -> "V8__invoices_kind_quote.sql";
        };
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

    private static UUID insert(String status, String number, boolean issued, String extra) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + SCHEMA + "\".invoices (id, number, status, issue_date, issued_at, paid_at, "
                + "cancelled_at, cancellation_reason, client_name, vat_registered, vat_rate, prices_include_vat"
                + extra + ") VALUES (?, ?, ?, " + (issued ? "current_date, now()" : "NULL, NULL") + ", "
                + ("PAID".equals(status) ? "now()" : "NULL") + ", "
                + ("CANCELLED".equals(status) ? "now(), 'motif'" : "NULL, NULL")
                + ", 'Client', false, 19.25, true" + (extra.isEmpty() ? "" : ", 'QUOTE'") + ")",
                id, number, status);
        return id;
    }

    @BeforeEach
    void legacyTenant() throws Exception {
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE"); // test database only
        jdbc.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        for (String v : List.of("V1", "V2", "V3", "V4", "V5", "V6", "V7")) {
            script(v);
        }
    }

    @Test
    @DisplayName("V8 on a tenant with DRAFT/ISSUED/PAID/CANCELLED invoices: rows untouched and INVOICE, constraints "
            + "validated, indexes created; re-run is a no-op")
    void upgradesExistingTenant() throws Exception {
        insert("DRAFT", null, false, "");
        insert("ISSUED", "FAC-2026-000001", true, "");
        insert("PAID", "FAC-2026-000002", true, "");
        insert("CANCELLED", "FAC-2026-000003", true, "");
        List<Map<String, Object>> before = jdbc.queryForList("SELECT id, number, status, issue_date, updated_at FROM \""
                + SCHEMA + "\".invoices ORDER BY id");

        script("V8");
        List<String> constraintsAfterFirst = constraints();
        script("V8"); // idempotent
        script("V9");
        script("V9"); // idempotent

        assertThat(jdbc.queryForList("SELECT id, number, status, issue_date, updated_at FROM \"" + SCHEMA
                + "\".invoices ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT DISTINCT kind FROM \"" + SCHEMA + "\".invoices", String.class))
                .containsExactly("INVOICE");
        assertThat(constraints()).isEqualTo(constraintsAfterFirst);
        assertThat(jdbc.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = '\"" + SCHEMA
                + "\".invoices'::regclass AND contype = 'c' AND NOT convalidated", String.class)).isEmpty();
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = 'invoices'",
                String.class, SCHEMA)).contains("idx_invoices_kind_status_created", "uq_invoices_source_quote",
                "idx_invoices_kind_created");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = "
                + "'idx_invoices_kind_created'", String.class, SCHEMA))
                .endsWith("(kind, created_at DESC, id DESC) WHERE (deleted_at IS NULL)");
        // exactly one status-list CHECK and one "issued fields" CHECK remain (the unnamed V1 ones were replaced)
        assertThat(constraintsAfterFirst).filteredOn(c -> c.contains("'ISSUED'") && c.contains("'CANCELLED'")
                && c.contains("'DRAFT'") && c.contains("ANY")).hasSize(1);
        assertThat(constraintsAfterFirst).filteredOn(c -> c.contains("number IS NOT NULL")).hasSize(1);
    }

    @Test
    @DisplayName("after V8: invoices keep the V1 rules; quotes never numbered; CONVERTED needs a linked quote; one "
            + "invoice per source quote")
    void constraintsEnforced() throws Exception {
        script("V8");
        String table = "\"" + SCHEMA + "\".invoices";
        // an ISSUED invoice without number is still refused
        assertThatThrownBy(() -> insert("ISSUED", null, false, "")).isInstanceOf(DataIntegrityViolationException.class);
        // quotes: DRAFT and CANCELLED without number accepted, a numbered or ISSUED quote refused
        UUID quote = insert("DRAFT", null, false, ", kind");
        insert("CANCELLED", null, false, ", kind");
        assertThatThrownBy(() -> insert("DRAFT", "FAC-2026-000009", false, ", kind"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("ISSUED", "FAC-2026-000010", true, ", kind"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET kind = 'AVOIR' WHERE id = ?", quote))
                .isInstanceOf(DataIntegrityViolationException.class);
        // CONVERTED: only a quote with its link
        assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET status = 'CONVERTED' WHERE id = ?", quote))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID invoice = insert("ISSUED", "FAC-2026-000011", true, "");
        jdbc.update("UPDATE " + table + " SET source_quote_id = ? WHERE id = ?", quote, invoice);
        assertThat(jdbc.update("UPDATE " + table + " SET status = 'CONVERTED', converted_invoice_id = ?, "
                + "converted_at = now() WHERE id = ?", invoice, quote)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET status = 'CONVERTED', "
                + "converted_invoice_id = ?, converted_at = now() WHERE id = ?", quote, invoice))
                .isInstanceOf(DataIntegrityViolationException.class);
        // a second invoice from the same quote is refused by the unique index
        UUID second = insert("ISSUED", "FAC-2026-000012", true, "");
        assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET source_quote_id = ? WHERE id = ?", quote,
                second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static List<String> constraints() {
        return jdbc.queryForList("SELECT conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = '\"" + SCHEMA + "\".invoices'::regclass AND contype = 'c' ORDER BY 1",
                String.class);
    }
}
