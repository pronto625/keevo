package com.keevo.finance;

import com.keevo.expenses.expense.adapter.out.persistence.JdbcExpenseTotals;
import com.keevo.finance.summary.adapter.out.persistence.RevenueSourceFinanceAdapter;
import com.keevo.finance.summary.application.FinanceAccess;
import com.keevo.finance.summary.application.FinanceSummaryService;
import com.keevo.finance.summary.domain.model.FinanceMath;
import com.keevo.finance.summary.domain.model.FinanceMath.Period;
import com.keevo.finance.summary.domain.model.FinancePeriods.Resolved;
import com.keevo.finance.summary.domain.model.FinanceSummary;
import com.keevo.reporting.revenue.FixedRevenueSource;
import com.keevo.reporting.revenue.adapter.out.persistence.RevenueRelations;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantFoundation;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story 20.11 — the finance summary on PostgreSQL with the real adapters: a tenant schema built from the foundation
 * scripts, invoicing V1–V14 and expenses V1. Revenue excl. VAT from the 20.9 source (invoice VAT, POS sale bridged to its
 * automatic invoice counted once with its VAT once, credit note of a PAID invoice), WAT civil dates, store filter,
 * series, expenses grouped in one query, and the plan checks of FUS-NFR11.
 */
@DisplayName("20.11 — finance summary on PostgreSQL (revenue source + expense totals)")
class FinanceSummaryPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_finance_2011";
    private static final String SCHEMA = "kv_fi2011";
    private static final String EMPTY_SCHEMA = "kv_fi2012";
    private static final Period SEPTEMBER = new Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

    private static DriverManagerDataSource admin;
    private static JdbcTemplate jdbc;

    private final StoreQueryPort stores = mock(StoreQueryPort.class);
    private FinanceSummaryService service;
    private UUID storeA;
    private UUID storeB;
    private final UUID seller = UUID.randomUUID();
    private int numbers;

    @BeforeAll
    static void tenant() throws Exception {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        admin = new DriverManagerDataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password());
        JdbcTemplate root = new JdbcTemplate(admin);
        root.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        root.execute("CREATE SCHEMA \"" + EMPTY_SCHEMA + "\"");
        TenantFoundation.upgrade(admin, SCHEMA);
        for (String script : List.of("V1__invoicing.sql", "V2__invoice_sending.sql", "V3__invoice_source_sale.sql",
                "V4__credit_notes.sql", "V5__sale_invoice_state.sql", "V6__invoice_sync_keyset.sql",
                "V7__invoice_soft_delete.sql", "V8__invoices_kind_quote.sql", "V9__invoices_kind_list_index.sql",
                "V10__invoice_payments.sql", "V11__invoice_discount_delivery_vat_due.sql",
                "V12__invoices_due_date_index.sql", "V13__invoicing_settings_derive_from_documents.sql",
                "V14__revenue_indexes.sql", "V15__invoice_templates_signatures_public_links.sql",
                "V16__invoice_store.sql")) {
            script("db/modules/invoicing/" + script);
        }
        script("db/modules/expenses/V1__expenses.sql");
        DriverManagerDataSource tenant = new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password());
        Properties props = new Properties();
        props.setProperty("currentSchema", SCHEMA + ",public");
        tenant.setConnectionProperties(props);
        jdbc = new JdbcTemplate(tenant);
    }

    @AfterAll
    static void drop() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    private static void script(String path) throws Exception {
        String sql = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = admin.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL search_path TO \"" + SCHEMA + "\"");
                s.execute(sql);
            }
            c.commit();
        }
    }

    @BeforeEach
    void clean() {
        TenantContext.setCurrentTenant(SCHEMA);
        // test database only
        jdbc.execute("TRUNCATE credit_note_items, credit_notes, invoice_payments, invoice_items, invoices, "
                + "invoicing_settings, sale_items, sales, expenses, stores CASCADE");
        storeA = UUID.randomUUID();
        storeB = UUID.randomUUID();
        Timestamp t = Timestamp.from(Instant.parse("2025-01-01T00:00:00Z"));
        for (UUID s : List.of(storeA, storeB)) {
            jdbc.update("INSERT INTO stores (id, created_at, updated_at, is_active, name, type) "
                    + "VALUES (?, ?, ?, true, ?, 'STORE')", s, t, t, s.equals(storeA) ? "Akwa" : "Bonapriso");
        }
        when(stores.findById(eq(SCHEMA), any())).thenReturn(Optional.of(mock(StoreView.class)));
        when(stores.namesByIds(eq(SCHEMA), any())).thenReturn(Map.of(storeA, "Akwa", storeB, "Bonapriso"));
        service = new FinanceSummaryService(new RevenueSourceFinanceAdapter(FixedRevenueSource.ALL, jdbc),
                new JdbcExpenseTotals(jdbc), stores);
        numbers = 0;
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** Dates form: compared with the window of the same length just before (Bref). */
    private static Resolved byDates(Period period) {
        return new Resolved(period, FinanceMath.previousPeriod(period.startDate(), period.endDate()));
    }

    private FinanceSummary summary(Resolved request, UUID storeId) {
        return service.summary(request, storeId, FinanceAccess.OWNER, SCHEMA);
    }

    /** September: VAT invoice, plain sale, bridged sale; previous period: PAID invoice with a credit note. */
    private void september() {
        invoice("ISSUED", LocalDate.of(2026, 9, 3), 11925, 1925, null);                  // HT 10 000
        sale(storeA, Instant.parse("2026-09-10T22:30:00Z"), 5000);                         // 23:30 WAT, 10 Sept.
        UUID bridged = sale(storeA, Instant.parse("2026-09-12T09:00:00Z"), 3000);
        invoice("PAID", LocalDate.of(2026, 9, 12), 3000, 479, bridged);                   // automatic invoice
        sale(storeB, Instant.parse("2026-09-30T23:30:00Z"), 7000);                         // 1 Oct. 00:30 WAT
        UUID august = invoice("PAID", LocalDate.of(2026, 8, 15), 10000, 0, null);
        creditNote(august, 2000, 0, Instant.parse("2026-08-20T10:00:00Z"));
        expense(storeA, "rent", "3000", LocalDate.of(2026, 9, 3));
        expense(storeB, "other", "1000.50", LocalDate.of(2026, 9, 10));
        expense(storeB, "other", "999", LocalDate.of(2026, 10, 1));                        // next month
        expense(storeA, "rent", "500", LocalDate.of(2026, 8, 31));                         // previous period
    }

    @Test
    @DisplayName("every store: HT = TTC − VAT, bridged sale once with its VAT once, WAT dates, Bref math, byStore "
            + "with « Sans boutique » so that the rows add up")
    void everyStore() {
        september();
        FinanceSummary s = summary(byDates(SEPTEMBER), null);

        assertThat(s.revenue().ttc()).isEqualByComparingTo("19925");          // 11 925 + 5 000 + 3 000
        assertThat(s.revenue().tax()).isEqualByComparingTo("2404");           // 1 925 + 479
        assertThat(s.revenue().excludingTax()).isEqualByComparingTo("17521");
        assertThat(s.revenue().paid()).isEqualByComparingTo("8000");          // the two sales; the invoice is ISSUED
        assertThat(s.revenue().unpaid()).isEqualByComparingTo("11925");
        assertThat(s.revenue().invoiceCount()).isEqualTo(3);
        assertThat(s.expenses().total()).isEqualByComparingTo("4000.5");
        assertThat(s.expenses().count()).isEqualTo(2);
        assertThat(s.result().net()).isEqualByComparingTo("13520.5");
        assertThat(s.result().marginPct()).isEqualByComparingTo("77.17");     // 13 520.5 / 17 521
        assertThat(s.comparison().previousPeriod().startDate()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(s.comparison().revenue().previous()).isEqualByComparingTo("8000"); // 10 000 − credit note 2 000
        assertThat(s.comparison().revenue().deltaPct()).isEqualByComparingTo("119.01"); // (17 521 − 8 000) / 8 000
        assertThat(s.comparison().expenses().previous()).isEqualByComparingTo("500");
        assertThat(s.comparison().result().previous()).isEqualByComparingTo("7500");
        assertThat(s.series()).hasSize(30);
        assertThat(point(s, "2026-09-03")).isEqualTo(new String[]{"10000", "3000", "7000"});
        assertThat(point(s, "2026-09-10")).isEqualTo(new String[]{"5000", "1000.5", "3999.5"});
        assertThat(point(s, "2026-09-12")).isEqualTo(new String[]{"2521", "0", "2521"});
        assertThat(s.byCategory()).extracting(FinanceSummary.CategoryShare::categoryLabel)
                .containsExactly("Loyer", "Autre");
        // stores: sales and expenses of each store; the invoice (no store until 20-14) in « Sans boutique »
        assertThat(s.byStore()).extracting(FinanceSummary.StoreResult::storeName)
                .containsExactly("Sans boutique", "Akwa", "Bonapriso");
        assertThat(s.byStore().get(0).storeId()).isNull();
        assertThat(s.byStore().get(0).revenue()).isEqualByComparingTo("10000");
        assertThat(s.byStore().get(1).revenue()).isEqualByComparingTo("7521");
        assertThat(s.byStore().get(2).result()).isEqualByComparingTo("-1000.5");
        assertThat(s.byStore().stream().map(FinanceSummary.StoreResult::revenue)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)).isEqualByComparingTo("17521");
    }

    @Test
    @DisplayName("store filter: the store-scoped source (sales with their bridge VAT) and the store's expenses")
    void oneStore() {
        september();
        FinanceSummary s = summary(byDates(SEPTEMBER), storeA);
        assertThat(s.revenue().ttc()).isEqualByComparingTo("8000");
        assertThat(s.revenue().excludingTax()).isEqualByComparingTo("7521");
        assertThat(s.expenses().total()).isEqualByComparingTo("3000");
        assertThat(s.comparison().expenses().previous()).isEqualByComparingTo("500");
        assertThat(s.comparison().revenue().deltaPct()).isNull();
        assertThat(s.byStore()).isEmpty();
    }

    @Test
    @DisplayName("PAID invoice and its credit note with VAT in the month; a partly paid invoice stays unpaid (Bref: "
            + "« payé » = status paid); non-zero trend vs the previous calendar month")
    void paidCreditNoteAndPartPayment() {
        UUID paid = invoice("PAID", LocalDate.of(2026, 9, 5), 11925, 1925, null);
        creditNote(paid, 2385, 385, Instant.parse("2026-09-20T10:00:00Z"));
        UUID partly = invoice("ISSUED", LocalDate.of(2026, 9, 8), 5000, 0, null);
        jdbc.update("INSERT INTO invoice_payments (id, invoice_id, amount, paid_at) VALUES (?, ?, 2000, ?)",
                UUID.randomUUID(), partly, Timestamp.from(Instant.parse("2026-09-09T10:00:00Z")));
        invoice("PAID", LocalDate.of(2026, 8, 1), 5000, 0, null); // previous calendar month (not in Aug 2–31)

        FinanceSummary s = summary(new Resolved(SEPTEMBER, new Period(LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31))), null);
        assertThat(s.revenue().ttc()).isEqualByComparingTo("14540");          // 11 925 − 2 385 + 5 000
        assertThat(s.revenue().tax()).isEqualByComparingTo("1540");           // 1 925 − 385
        assertThat(s.revenue().excludingTax()).isEqualByComparingTo("13000");
        assertThat(s.revenue().paid()).isEqualByComparingTo("9540");          // the PAID invoice net of its credit note
        assertThat(s.revenue().unpaid()).isEqualByComparingTo("5000");        // the partly paid invoice, whole
        assertThat(s.revenue().invoiceCount()).isEqualTo(2);
        assertThat(s.comparison().previousPeriod().startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(s.comparison().revenue().previous()).isEqualByComparingTo("5000");
        assertThat(s.comparison().revenue().deltaPct()).isEqualByComparingTo("160");
        assertThat(summary(byDates(SEPTEMBER), null).comparison().revenue().previous()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("VAT-registered seller: an unbridged sale's VAT = TTC − HALF_UP(TTC × 100 / (100 + rate)); a bridged "
            + "sale keeps its invoice's VAT; same under a store filter")
    void unbridgedSaleOfVatRegisteredSeller() {
        jdbc.update("INSERT INTO invoicing_settings (id, vat_registered, vat_rate) VALUES (1, true, 19.25)");
        sale(storeA, Instant.parse("2026-09-04T09:00:00Z"), 11925);                        // VAT 1 925
        sale(storeA, Instant.parse("2026-09-04T10:00:00Z"), 1000);                         // 1 000 − 839 = 161
        UUID bridged = sale(storeA, Instant.parse("2026-09-05T09:00:00Z"), 3000);
        invoice("PAID", LocalDate.of(2026, 9, 5), 3000, 0, bridged);                      // its invoice: no VAT

        FinanceSummary all = summary(byDates(SEPTEMBER), null);
        assertThat(all.revenue().tax()).isEqualByComparingTo("2086");
        assertThat(all.revenue().excludingTax()).isEqualByComparingTo("13839");
        FinanceSummary one = summary(byDates(SEPTEMBER), storeA);
        assertThat(one.revenue().tax()).isEqualByComparingTo("2086");

        jdbc.update("UPDATE invoicing_settings SET vat_registered = false");
        assertThat(summary(byDates(SEPTEMBER), null).revenue().tax()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a year: monthly series of 12 keys; a tenant without the expenses table reads zero expenses")
    void yearAndNoExpensesTable() {
        september();
        FinanceSummary year = summary(byDates(new Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))), null);
        assertThat(year.period().granularity()).isEqualTo("month");
        assertThat(year.series()).hasSize(12);
        assertThat(point(year, "2026-10")).isEqualTo(new String[]{"7000", "999", "6001"});

        TenantContext.setCurrentTenant(EMPTY_SCHEMA);
        JdbcExpenseTotals totals = new JdbcExpenseTotals(jdbc);
        assertThat(totals.breakdown(EMPTY_SCHEMA, LocalDate.of(2026, 8, 1), SEPTEMBER.startDate(),
                SEPTEMBER.endDate(), null, com.keevo.expenses.api.ExpenseTotalsQueryPort.Bucket.DAY))
                .isEqualTo(com.keevo.expenses.api.ExpenseTotalsQueryPort.Breakdown.EMPTY);
    }


    @Test
    @DisplayName("FUS-NFR11: the 20.9 consumers' relation has no bridge join; the taxed relation reaches the active "
            + "automatic invoice through its unique index; windows stay on their indexes; expenses on their date index")
    void plans() throws Exception {
        String window = " r WHERE r.occurred_at >= '2026-09-01T00:00:00Z'::timestamptz";
        assertThat(explain("SELECT COALESCE(SUM(r.total_amount), 0) FROM " + RevenueRelations.documents(true, true)
                + window)).doesNotContain("Left Join").contains("idx_sales_completed_occurred_at");
        assertThat(explain("SELECT COALESCE(SUM(r.tax_amount), 0) FROM " + RevenueRelations.taxedDocuments(true, true)
                + window)).contains("Left Join").contains("source_sale")
                .contains("idx_sales_completed_occurred_at").contains("idx_invoices_revenue_day");
        assertThat(explain("SELECT SUM(amount) FROM expenses WHERE archived_at IS NULL "
                + "AND expense_date >= '2026-09-01' AND expense_date <= '2026-09-30'"))
                .contains("Index Cond: ((expense_date >= "); // the date range is an index range (V1 partial indexes)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────

    private static String explain(String sql) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET search_path TO \"" + SCHEMA + "\"");
            s.execute("SET enable_seqscan = off"); // tiny tables: make the planner show the usable index paths
            try (var rs = s.executeQuery("EXPLAIN " + sql)) {
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
            }
        }
        return plan.toString();
    }

    private static String[] point(FinanceSummary s, String key) {
        FinanceSummary.SeriesPoint p = s.series().stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
        return new String[]{p.revenue().toPlainString(), p.expenses().toPlainString(), p.result().toPlainString()};
    }

    private UUID sale(UUID storeId, Instant occurredAt, int total) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sales (id, created_at, employee_id, occurred_at, payment_mode, status, store_id, "
                        + "total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, ?, 0)",
                id, Timestamp.from(occurredAt), seller, Timestamp.from(occurredAt), storeId, total);
        return id;
    }

    private UUID invoice(String status, LocalDate issueDate, long ttc, long vat, UUID sourceSale) {
        UUID id = UUID.randomUUID();
        Timestamp issuedAt = Timestamp.from(issueDate.atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant());
        jdbc.update("INSERT INTO invoices (id, number, status, issue_date, issued_at, paid_at, payment_method, "
                        + "client_name, vat_registered, vat_rate, prices_include_vat, total_ht, total_vat, total_ttc, "
                        + "source_sale_id, created_by, kind) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'Client', true, 19.25, true, ?, ?, ?, ?, ?, 'INVOICE')",
                id, "FAC-2026-" + String.format("%06d", ++numbers), status, Date.valueOf(issueDate), issuedAt,
                "PAID".equals(status) ? issuedAt : null, "PAID".equals(status) ? "Espèces" : null,
                ttc - vat, vat, ttc, sourceSale, seller);
        return id;
    }

    private void creditNote(UUID invoice, long ttc, long vat, Instant issuedAt) {
        jdbc.update("INSERT INTO credit_notes (id, number, invoice_id, reason, total_ht, total_vat, total_ttc, "
                        + "issued_at) VALUES (?, ?, ?, 'Retour', ?, ?, ?, ?)",
                UUID.randomUUID(), "AV-2026-" + String.format("%06d", ++numbers), invoice, ttc - vat, vat, ttc,
                Timestamp.from(issuedAt));
    }

    private void expense(UUID storeId, String category, String amount, LocalDate date) {
        jdbc.update("INSERT INTO expenses (id, store_id, category, amount, expense_date, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), storeId, category, new BigDecimal(amount), date, seller);
    }
}
