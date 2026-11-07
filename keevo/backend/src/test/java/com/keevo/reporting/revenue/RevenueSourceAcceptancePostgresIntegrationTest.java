package com.keevo.reporting.revenue;

import com.keevo.reporting.dashboard.application.service.DashboardService;
import com.keevo.reporting.dashboard.domain.model.DashboardSummary;
import com.keevo.reporting.dashboard.domain.model.DashboardSummary.DailyCAEntry;
import com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase.GetDashboardSummaryQuery;
import com.keevo.reporting.leaderboard.adapter.out.persistence.JdbcSellerLeaderboardRepository;
import com.keevo.reporting.leaderboard.domain.port.out.SellerLeaderboardRepository.RawSellerRow;
import com.keevo.reporting.profitability.adapter.out.persistence.JdbcProfitabilityRepository;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository.RawProfitabilityRow;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository.RawRevenueSplit;
import com.keevo.reporting.revenue.domain.port.out.RevenueQueryPort;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantFoundation;
import org.junit.jupiter.api.AfterAll;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.9 (R4, FUS-FR24) — acceptance of the single revenue source on PostgreSQL: a tenant schema built from the
 * foundation scripts plus invoicing V1–V13, read by the real consumers (owner dashboard, seller ranking, product
 * profitability and its summary). Scenarios: POS only, documents only, POS → invoice bridge (no double count),
 * credit note deducted, quote not counted, cancelled / draft not counted, invoice line without product (revenue yes,
 * margin unknown), invoice civil date (FUS-NFR9). Plus the comparison harness of AC2 on the synthetic Bref fixture
 * (the prod-copy run is 24.12).
 */
@DisplayName("20.9 — revenue source (sales ∪ invoices − credit notes) on PostgreSQL")
class RevenueSourceAcceptancePostgresIntegrationTest {

    private static final String TEST_DB = "keevo_revenue_r4";
    private static final String SCHEMA = "kv_rv2009";

    /** Wednesday 2026-11-11 13:00 WAT. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);
    private static final Instant TODAY_10H = Instant.parse("2026-11-11T09:00:00Z");

    private static DriverManagerDataSource admin;
    private static JdbcTemplate jdbc;

    private UUID store;
    private UUID seller;
    private UUID product;
    private int numbers;

    @BeforeAll
    static void tenant() throws Exception {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        admin = new DriverManagerDataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password());
        JdbcTemplate root = new JdbcTemplate(admin);
        root.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        // public tables the seller-name joins read (ExportValues.memberJoin) — empty here
        root.execute("CREATE TABLE public.tenants (id UUID PRIMARY KEY, schema_name VARCHAR(64))");
        root.execute("CREATE TABLE public.user_tenant_memberships (user_id UUID, tenant_id UUID, role VARCHAR(20))");
        TenantFoundation.upgrade(admin, SCHEMA);
        for (String script : List.of("V1__invoicing.sql", "V2__invoice_sending.sql", "V3__invoice_source_sale.sql",
                "V4__credit_notes.sql", "V5__sale_invoice_state.sql", "V6__invoice_sync_keyset.sql",
                "V7__invoice_soft_delete.sql", "V8__invoices_kind_quote.sql", "V9__invoices_kind_list_index.sql",
                "V10__invoice_payments.sql", "V11__invoice_discount_delivery_vat_due.sql",
                "V12__invoices_due_date_index.sql", "V14__revenue_indexes.sql", "V14__revenue_indexes.sql")) {
            invoicingScript(script); // V13 twice: idempotent
        }
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

    private static void invoicingScript(String name) throws Exception {
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

    @BeforeEach
    void clean() {
        // test database only
        jdbc.execute("TRUNCATE credit_note_items, credit_notes, invoice_payments, invoice_items, invoices, "
                + "sale_items, sales, products, stores CASCADE");
        store = UUID.randomUUID();
        Timestamp t = Timestamp.from(Instant.parse("2025-01-01T00:00:00Z"));
        jdbc.update("INSERT INTO stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, ?, ?, true, 'Boutique R4', 'STORE')", store, t, t);
        product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, sku, price, buy_price, transport_cost) "
                + "VALUES (?, 'Riz 25 kg', 'RIZ25', 15000, 9000, 1000)", product);
        seller = UUID.randomUUID();
        numbers = 0;
    }

    // ── scenarios ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POS only: COMPLETED sales count, cancelled / pending do not — same as V1 with the sales-only source")
    void posOnly() {
        sale(TODAY_10H, 1000, "COMPLETED");
        sale(TODAY_10H, 2000, "COMPLETED");
        sale(TODAY_10H, 9000, "CANCELLED");
        sale(TODAY_10H, 7000, "PENDING_VALIDATION");

        for (RevenueQueryPort source : List.of(FixedRevenueSource.ALL, FixedRevenueSource.POS_ONLY)) {
            DashboardSummary d = dashboard(source);
            assertThat(d.todayCA()).isEqualTo(3000);
            assertThat(d.todaySalesCount()).isEqualTo(2);
            assertThat(storeToday(d)).isEqualTo(3000);
        }
    }

    @Test
    @DisplayName("documents only: ISSUED and PAID invoices count on their issue date; drafts, cancelled, deleted do not")
    void documentsOnly() {
        invoice("ISSUED", TODAY, 10_000, null, line(null, 1, 10_000));
        invoice("PAID", TODAY, 5_000, null, line(null, 1, 5_000));
        invoice("DRAFT", null, 70_000, null, line(null, 1, 70_000));
        invoice("CANCELLED", TODAY, 80_000, null, line(null, 1, 80_000));
        UUID deleted = invoice("DRAFT", null, 90_000, null, line(null, 1, 90_000));
        jdbc.update("UPDATE invoices SET deleted_at = now() WHERE id = ?", deleted);

        DashboardSummary d = dashboard(FixedRevenueSource.DOCUMENTS_ONLY);
        assertThat(d.todayCA()).isEqualTo(15_000);
        assertThat(d.todaySalesCount()).isEqualTo(2);
        assertThat(d.periodTotals().allTime()).isEqualTo(new DashboardSummary.PeriodTotal(15_000, 2));
        // invoices carry no store: in the all-stores figures only
        assertThat(storeToday(d)).isZero();
    }

    @Test
    @DisplayName("POS → invoice bridge: the sale and its automatic invoice count once; its credit note is not deducted")
    void bridgeNoDoubleCount() {
        UUID sale = sale(TODAY_10H, 3000, "COMPLETED");
        UUID auto = invoice("ISSUED", TODAY, 3000, sale, line(product, 1, 3000));
        creditNote(auto, 3000, TODAY_10H, 1, 1, 3000); // e.g. a sale correction: the sale side already counts it

        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        assertThat(d.todayCA()).isEqualTo(3000);
        assertThat(d.todaySalesCount()).isEqualTo(1);
        assertThat(sellers(FixedRevenueSource.ALL)).singleElement()
                .satisfies(r -> assertThat(r.totalRevenue()).isEqualTo(3000));
    }

    @Test
    @DisplayName("credit note: deducted on its own date, counts no transaction, from the invoice's seller")
    void creditNoteDeducted() {
        UUID inv = invoice("ISSUED", TODAY, 10_000, null, line(product, 2, 10_000));
        creditNote(inv, 4_000, TODAY_10H, 1, 1, 4_000);

        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        assertThat(d.todayCA()).isEqualTo(6_000);
        assertThat(d.todaySalesCount()).isEqualTo(1);
        assertThat(sellers(FixedRevenueSource.ALL)).singleElement().satisfies(r -> {
            assertThat(r.sellerId()).isEqualTo(seller);
            assertThat(r.totalRevenue()).isEqualTo(6_000);
            assertThat(r.salesCount()).isEqualTo(1);
        });
        // per product: 2 sold − 1 credited, 10 000 − 4 000
        assertThat(products(FixedRevenueSource.ALL)).singleElement().satisfies(p -> {
            assertThat(p.unitsSold()).isEqualTo(1);
            assertThat(p.totalRevenue()).isEqualTo(6_000);
        });
    }

    @Test
    @DisplayName("quote: never counted; its converted invoice counts once")
    void quoteNotCounted() {
        quote("DRAFT", 50_000, null);
        UUID converted = invoice("ISSUED", TODAY, 20_000, null, line(null, 1, 20_000));
        quote("CONVERTED", 20_000, converted);

        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        assertThat(d.todayCA()).isEqualTo(20_000);
        assertThat(d.todaySalesCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("invoice line without product: counted in revenue, absent from the product list, « marge inconnue »")
    void lineWithoutProduct() {
        invoice("ISSUED", TODAY, 20_000, null, line(product, 1, 12_000), line(null, 1, 8_000));

        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        assertThat(d.todayCA()).isEqualTo(20_000);
        assertThat(d.weeklyTopProducts()).extracting(DashboardSummary.TopProductEntry::productId)
                .containsExactly(product.toString());

        List<RawProfitabilityRow> rows = products(FixedRevenueSource.ALL);
        assertThat(rows).singleElement().satisfies(p -> {
            assertThat(p.productId()).isEqualTo(product);
            assertThat(p.totalRevenue()).isEqualTo(12_000);
            assertThat(p.totalCost()).isEqualTo(10_000); // (9 000 + 1 000) × 1
        });
        RawRevenueSplit split = new JdbcProfitabilityRepository(jdbc, FixedRevenueSource.ALL)
                .findRevenueSplit(SCHEMA, TODAY.minusDays(6), TODAY, null);
        assertThat(split).isEqualTo(new RawRevenueSplit(20_000, 12_000, 10_000, 8_000, 1));
    }

    @Test
    @DisplayName("mixed tenant: POS sale + document + bridged invoice + credit note, every consumer agrees")
    void mixedEveryConsumerAgrees() {
        UUID sale = sale(TODAY_10H, 3000, "COMPLETED");
        invoice("ISSUED", TODAY, 3000, sale, line(product, 1, 3000)); // automatic invoice of the sale
        UUID doc = invoice("PAID", TODAY, 15_000, null, line(product, 1, 15_000));
        creditNote(doc, 5_000, TODAY_10H, 1, 1, 5_000);
        quote("DRAFT", 99_000, null);

        long expected = 3000 + 15_000 - 5_000;
        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        assertThat(d.todayCA()).isEqualTo(expected);
        assertThat(d.periodTotals().month()).isEqualTo(new DashboardSummary.PeriodTotal(expected, 2));
        assertThat(sellers(FixedRevenueSource.ALL).stream().mapToLong(RawSellerRow::totalRevenue).sum())
                .isEqualTo(expected);
        assertThat(new JdbcProfitabilityRepository(jdbc, FixedRevenueSource.ALL)
                .findRevenueSplit(SCHEMA, TODAY, TODAY, null).totalRevenue()).isEqualTo(expected);
        // module autonomy: the same tenant read without the invoicing module = its POS sales
        assertThat(dashboard(FixedRevenueSource.POS_ONLY).todayCA()).isEqualTo(3000);
    }

    @Test
    @DisplayName("FUS-NFR9: an invoice counts on its civil issue date, not on the instant it was issued")
    void invoiceCivilDate() {
        UUID inv = invoice("ISSUED", TODAY.minusDays(1), 4_000, null, line(null, 1, 4_000));
        jdbc.update("UPDATE invoices SET issued_at = ? WHERE id = ?", Timestamp.from(TODAY_10H), inv);

        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        assertThat(d.todayCA()).isZero();
        assertThat(d.yesterdayCA()).isEqualTo(4_000);
        assertThat(d.dailyCALast30()).containsExactly(new DailyCAEntry("2026-11-10", 4_000));
    }

    @Test
    @DisplayName("FUS-NFR11: a window on the source reaches the V14 indexes through the UNION ALL (pushed down)")
    void windowUsesTheV13Indexes() throws Exception {
        String sql = "EXPLAIN SELECT COALESCE(SUM(r.total_amount), 0) FROM " + FixedRevenueSource.ALL.documents()
                + " r WHERE r.occurred_at >= '2026-11-10T23:00:00Z'::timestamptz";
        StringBuilder plan = new StringBuilder();
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET search_path TO \"" + SCHEMA + "\"");
            s.execute("SET enable_seqscan = off"); // tiny tables: make the planner show the usable index paths
            try (var rs = s.executeQuery(sql)) {
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
            }
        }
        // invoices: the window is pushed into the branch and matches the V14 expression index; credit notes: the
        // window is pushed too (on empty tables the planner may prefer the invoice join over idx_credit_notes_issued_at)
        assertThat(plan.toString()).contains("Index Scan using idx_invoices_revenue_day")
                .contains("(issued_at >= ").contains("Index Cond: (occurred_at >= ");
    }

    /**
     * AC2 harness — the invoices of owner A of the synthetic Bref fixture
     * ({@code _bmad-output/planning-artifacts/research/fusion-mesures/validation/fixture-bref-synthetique.sql}, shop
     * A1, CM): Bref's monthly revenue rule ({@code owner-dashboard.service.ts}: not deleted, {@code type = 'sale'},
     * {@code status <> 'cancelled'}, Σ {@code total} by {@code date}) computed here on the rows, against the Keevo
     * monthly chart of the same documents mapped as 24.4 will import them (sale → INVOICE ISSUED/PAID on
     * {@code issue_date = date}, quote → QUOTE). 24.12 replays it on the prod copy.
     */
    @Test
    @DisplayName("AC2 harness: Keevo monthly revenue = Bref's rule on the synthetic Bref fixture")
    void brefFixtureMonthlyRevenue() {
        record BrefInvoice(String status, String type, long total, LocalDate date) {}
        List<BrefInvoice> bref = List.of(
                new BrefInvoice("paid", "sale", 1193, LocalDate.of(2026, 11, 2)),     // I1
                new BrefInvoice("pending", "sale", 1500, LocalDate.of(2026, 9, 1)),   // I2
                new BrefInvoice("pending", "quote", 2000, LocalDate.of(2026, 10, 1)), // I4
                new BrefInvoice("paid", "sale", 0, LocalDate.of(2026, 10, 2)),        // I5 (fully discounted)
                new BrefInvoice("pending", "sale", 1193, LocalDate.of(2026, 10, 26)));// I6

        Map<String, Long> brefMonthly = new TreeMap<>();
        for (BrefInvoice b : bref) {
            if (b.type().equals("sale") && !b.status().equals("cancelled")) {
                brefMonthly.merge(b.date().withDayOfMonth(1).toString(), b.total(), Long::sum);
            }
            if (b.type().equals("quote")) {
                quote("DRAFT", b.total(), null);
            } else {
                invoice(b.status().equals("paid") ? "PAID" : "ISSUED", b.date(), b.total(), null,
                        line(null, 1, b.total()));
            }
        }

        Map<String, Long> keevoMonthly = new TreeMap<>();
        dashboard(FixedRevenueSource.ALL).monthlyCA().forEach(e -> keevoMonthly.put(e.date(), e.amount()));
        assertThat(keevoMonthly).isEqualTo(brefMonthly)
                .containsEntry("2026-09-01", 1500L).containsEntry("2026-10-01", 1193L)
                .containsEntry("2026-11-01", 1193L);
    }

    // ── consumers ───────────────────────────────────────────────────────────────

    private DashboardSummary dashboard(RevenueQueryPort source) {
        return new DashboardService(jdbc, CLOCK, source).execute(new GetDashboardSummaryQuery(SCHEMA));
    }

    private long storeToday(DashboardSummary d) {
        return d.storeOverviews().stream().filter(o -> o.storeId().equals(store.toString()))
                .findFirst().orElseThrow().todayCA();
    }

    private List<RawSellerRow> sellers(RevenueQueryPort source) {
        return new JdbcSellerLeaderboardRepository(jdbc, source).findRawSellers(TODAY.minusDays(6), TODAY, null);
    }

    private List<RawProfitabilityRow> products(RevenueQueryPort source) {
        return new JdbcProfitabilityRepository(jdbc, source).findRawByPeriod(SCHEMA, TODAY.minusDays(6), TODAY, null);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private record Line(UUID productId, int quantity, long totalTtc) {}

    private static Line line(UUID productId, int quantity, long totalTtc) {
        return new Line(productId, quantity, totalTtc);
    }

    private UUID sale(Instant occurredAt, int total, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sales (id, created_at, employee_id, occurred_at, payment_mode, status, store_id, "
                        + "total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', ?, ?, ?, 0)",
                id, Timestamp.from(occurredAt), seller, Timestamp.from(occurredAt), status, store, total);
        jdbc.update("INSERT INTO sale_items (sale_id, product_id, product_name, applied_unit_price, quantity, subtotal) "
                + "VALUES (?, ?, 'Riz 25 kg', ?, 1, ?)", id, product, total, total);
        return id;
    }

    private UUID invoice(String status, LocalDate issueDate, long total, UUID sourceSale, Line... lines) {
        UUID id = UUID.randomUUID();
        boolean issued = !"DRAFT".equals(status);
        Timestamp issuedAt = issued ? Timestamp.from(issueDate.atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant())
                : null;
        jdbc.update("INSERT INTO invoices (id, number, status, issue_date, issued_at, paid_at, payment_method, "
                        + "cancelled_at, cancellation_reason, client_name, vat_registered, vat_rate, prices_include_vat, "
                        + "total_ht, total_vat, total_ttc, source_sale_id, created_by, kind) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'Client', false, 0, true, ?, 0, ?, ?, ?, 'INVOICE')",
                id, issued ? "FAC-2026-" + String.format("%06d", ++numbers) : null, status,
                issued ? Date.valueOf(issueDate) : null, issuedAt,
                "PAID".equals(status) ? issuedAt : null, "PAID".equals(status) ? "Espèces" : null,
                "CANCELLED".equals(status) ? issuedAt : null, "CANCELLED".equals(status) ? "Erreur" : null,
                total, total, sourceSale, seller);
        int position = 0;
        for (Line l : lines) {
            jdbc.update("INSERT INTO invoice_items (id, invoice_id, position, description, quantity, unit_price, "
                            + "vat_treatment, vat_rate, total_ht, total_vat, total_ttc, product_id) "
                            + "VALUES (?, ?, ?, 'Article', ?, ?, 'STANDARD', 0, ?, 0, ?, ?)",
                    UUID.randomUUID(), id, ++position, BigDecimal.valueOf(l.quantity()),
                    l.totalTtc() / l.quantity(), l.totalTtc(), l.totalTtc(), l.productId());
        }
        return id;
    }

    private void quote(String status, long total, UUID convertedInvoice) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO invoices (id, status, client_name, vat_registered, vat_rate, prices_include_vat, "
                        + "total_ht, total_vat, total_ttc, created_by, kind, converted_invoice_id, converted_at) "
                        + "VALUES (?, ?, 'Client', false, 0, true, ?, 0, ?, ?, 'QUOTE', ?, ?)",
                id, status, total, total, seller, convertedInvoice,
                convertedInvoice != null ? Timestamp.from(TODAY_10H) : null);
        jdbc.update("INSERT INTO invoice_items (id, invoice_id, position, description, quantity, unit_price, "
                        + "vat_treatment, vat_rate, total_ht, total_vat, total_ttc) "
                        + "VALUES (?, ?, 1, 'Article', 1, ?, 'STANDARD', 0, ?, 0, ?)",
                UUID.randomUUID(), id, total, total, total);
    }

    private void creditNote(UUID invoice, long total, Instant issuedAt, int invoicePosition, int quantity,
                            long lineTotal) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO credit_notes (id, number, invoice_id, reason, total_ht, total_vat, total_ttc, "
                        + "issued_at) VALUES (?, ?, ?, 'Retour', ?, 0, ?, ?)",
                id, "AV-2026-" + String.format("%06d", ++numbers), invoice, total, total, Timestamp.from(issuedAt));
        jdbc.update("INSERT INTO credit_note_items (credit_note_id, position, invoice_position, description, quantity, "
                        + "unit_price, vat_treatment, vat_rate, total_ht, total_vat, total_ttc) "
                        + "VALUES (?, 1, ?, 'Article', ?, ?, 'STANDARD', 0, ?, 0, ?)",
                id, invoicePosition, BigDecimal.valueOf(quantity), lineTotal / quantity, lineTotal, lineTotal);
    }
}
