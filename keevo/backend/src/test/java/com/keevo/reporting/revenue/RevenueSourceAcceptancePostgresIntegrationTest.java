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
import com.keevo.shared.capability.revenue.RevenueQueryPort;
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
 * foundation scripts plus invoicing V1–V14, read by the real consumers (owner dashboard, seller ranking, product
 * profitability and its summary). Scenarios: POS only, documents only, POS → invoice bridge (no double count),
 * credit note deducted, quote not counted, cancelled / draft not counted, invoice line without product (revenue yes,
 * margin unknown), invoice civil date (FUS-NFR9). Plus the comparison harness of AC2 on the synthetic Bref fixture
 * (the prod-copy run is 24.12). Story 20.14: documents of a store (invoicing V16) in the store figures, the clients
 * export and the hourly store activity on the same source.
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
                "V12__invoices_due_date_index.sql", "V13__invoicing_settings_derive_from_documents.sql",
                "V14__revenue_indexes.sql", "V14__revenue_indexes.sql", "V16__invoice_store.sql",
                "V16__invoice_store.sql")) {
            invoicingScript(script); // V14 and V16 twice: idempotent
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
                + "sale_items, sales, products, employees, stores CASCADE");
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
    @DisplayName("POS → invoice bridge: the sale and its automatic invoice count once; an automatic credit note of it "
            + "is not deducted")
    void bridgeNoDoubleCount() {
        UUID sale = sale(TODAY_10H, 3000, "COMPLETED");
        UUID auto = invoice("ISSUED", TODAY, 3000, sale, line(product, 1, 3000));
        // sale cancelled / corrected: the bridge's credit note carries its event key, the sale side already counts it
        creditNote(auto, 3000, TODAY_10H, 1, 1, 3000, "sale.corrected:" + sale);

        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        assertThat(d.todayCA()).isEqualTo(3000);
        assertThat(d.todaySalesCount()).isEqualTo(1);
        assertThat(sellers(FixedRevenueSource.ALL)).singleElement()
                .satisfies(r -> assertThat(r.totalRevenue()).isEqualTo(3000));
    }

    @Test
    @DisplayName("POS → invoice bridge: a manual credit note on the automatic invoice is deducted (the sale stays "
            + "COMPLETED)")
    void bridgeManualCreditNoteDeducted() {
        UUID sale = sale(TODAY_10H, 3000, "COMPLETED");
        UUID auto = invoice("ISSUED", TODAY, 3000, sale, line(product, 1, 3000));
        creditNote(auto, 1000, TODAY_10H, 1, 1, 1000); // IssueCreditNoteUseCase: source_event NULL

        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        assertThat(d.todayCA()).isEqualTo(2000);
        assertThat(d.todaySalesCount()).isEqualTo(1);
        assertThat(sellers(FixedRevenueSource.ALL)).singleElement()
                .satisfies(r -> assertThat(r.totalRevenue()).isEqualTo(2000));
        assertThat(products(FixedRevenueSource.ALL)).singleElement().satisfies(p -> {
            assertThat(p.unitsSold()).isZero(); // 1 sold at the till − 1 credited
            assertThat(p.totalRevenue()).isEqualTo(2000);
        });
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
    void windowUsesTheV14Indexes() throws Exception {
        String sql = "EXPLAIN SELECT COALESCE(SUM(r.total_amount), 0) FROM " + FixedRevenueSource.ALL.current().documents()
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
        // (20.14: V16 adds idx_invoices_store_revenue_day, whose second column is the same expression — on empty
        // tables the planner may take either; both are index conditions on the civil day)
        assertThat(plan.toString()).containsPattern("Index Scan using idx_invoices_(store_)?revenue_day")
                .contains("(issued_at >= ").contains("idx_sales_completed_occurred_at");
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

    @Test
    @DisplayName("upper bound: a post-dated invoice (issue date tomorrow) inflates neither today, month nor all time")
    void postDatedInvoiceExcluded() {
        invoice("ISSUED", TODAY, 2_000, null, line(null, 1, 2_000));
        invoice("ISSUED", TODAY.plusDays(1), 50_000, null, line(null, 1, 50_000));

        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        assertThat(d.todayCA()).isEqualTo(2_000);
        assertThat(d.periodTotals().month()).isEqualTo(new DashboardSummary.PeriodTotal(2_000, 1));
        assertThat(d.periodTotals().allTime()).isEqualTo(new DashboardSummary.PeriodTotal(2_000, 1));
        assertThat(d.dailyCALast30()).containsExactly(new DailyCAEntry("2026-11-11", 2_000));
        assertThat(d.averageBasketMonth()).isEqualTo(2_000);
    }

    @Test
    @DisplayName("credit notes larger than the period's revenue: average basket 0, never negative")
    void negativeRevenueBasket() {
        UUID inv = invoice("ISSUED", TODAY.minusMonths(1), 10_000, null, line(null, 1, 10_000));
        invoice("ISSUED", TODAY, 1_000, null, line(null, 1, 1_000));
        creditNote(inv, 10_000, TODAY_10H, 1, 1, 10_000);

        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        assertThat(d.periodTotals().month()).isEqualTo(new DashboardSummary.PeriodTotal(-9_000, 1));
        assertThat(d.averageBasketMonth()).isZero();
    }

    @Test
    @DisplayName("two stores: per-store today / yesterday, employees, status from sales only; top and worst products")
    void twoStoreOverviewAndProductRanking() {
        UUID storeB = insertStore("Boutique B");
        employee(store);
        employee(store);
        employee(storeB);
        UUID p2 = insertProduct("Huile 5 L");
        UUID p3 = insertProduct("Savon");
        Instant yesterday10h = TODAY_10H.minusSeconds(86_400);
        saleOf(store, TODAY_10H, product, 5, 1_000);       // A today 5 000
        saleOf(store, yesterday10h, p2, 2, 5_000);         // A yesterday 10 000 → ratio 0.5 → ATTENTION
        saleOf(storeB, TODAY_10H, p2, 1, 1_000);           // B today 1 000
        saleOf(storeB, yesterday10h, product, 1, 4_000);   // B yesterday 4 000 → ratio 0.25 → EN_BAISSE
        UUID inv = invoice("ISSUED", TODAY, 50_000, null, line(p3, 1, 50_000)); // all stores only
        creditNote(inv, 50_000, TODAY_10H, 1, 1, 50_000);  // p3: 1 − 1 = 0 units

        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        var a = overview(d, store);
        var b = overview(d, storeB);
        assertThat(List.of(a.todayCA(), a.yesterdayCA(), (long) a.employeeCount())).containsExactly(5_000L, 10_000L, 2L);
        assertThat(a.statusLevel()).isEqualTo("ATTENTION");
        assertThat(List.of(b.todayCA(), b.yesterdayCA(), (long) b.employeeCount())).containsExactly(1_000L, 4_000L, 1L);
        assertThat(b.statusLevel()).isEqualTo("EN_BAISSE");
        assertThat(d.todayCA()).isEqualTo(6_000); // 5 000 + 1 000 + 50 000 − 50 000

        // 7 days: product 6 units, p2 3 units, p3 0 units (sold then credited)
        assertThat(d.weeklyTopProducts()).extracting(DashboardSummary.TopProductEntry::productName)
                .containsExactly("Riz 25 kg", "Huile 5 L", "Savon");
        assertThat(d.weeklyWorstProducts()).extracting(DashboardSummary.TopProductEntry::productName)
                .containsExactly("Huile 5 L", "Riz 25 kg");
    }

    @Test
    @DisplayName("store-filtered revenue split: the store's sales only; all stores add the documents")
    void storeFilteredRevenueSplit() {
        saleOf(store, TODAY_10H, product, 1, 3_000);
        invoice("ISSUED", TODAY, 20_000, null, line(product, 1, 12_000), line(null, 1, 8_000));
        JdbcProfitabilityRepository repo = new JdbcProfitabilityRepository(jdbc, FixedRevenueSource.ALL);

        assertThat(repo.findRevenueSplit(SCHEMA, TODAY, TODAY, store))
                .isEqualTo(new RawRevenueSplit(3_000, 3_000, 10_000, 0, 0));
        assertThat(repo.findRevenueSplit(SCHEMA, TODAY, TODAY, null))
                .isEqualTo(new RawRevenueSplit(23_000, 15_000, 20_000, 8_000, 1));
    }

    @Test
    @DisplayName("profitability store comparison, 7-day margin and top store: same on a POS and on a mixed tenant "
            + "(store-scoped), the margin without store adds the invoice lines")
    void profitabilityStoreQueriesPosAndMixed() {
        saleOf(store, TODAY_10H, product, 1, 15_000); // margin 15 000 − 10 000 = 5 000
        JdbcProfitabilityRepository pos = new JdbcProfitabilityRepository(jdbc, FixedRevenueSource.POS_ONLY);
        JdbcProfitabilityRepository mixed = new JdbcProfitabilityRepository(jdbc, FixedRevenueSource.ALL);

        for (int run = 0; run < 2; run++) {
            for (JdbcProfitabilityRepository repo : List.of(pos, mixed)) {
                assertThat(repo.findRawStoreByPeriod(SCHEMA, TODAY.minusDays(6), TODAY)).singleElement()
                        .satisfies(r -> {
                            assertThat(r.storeId()).isEqualTo(store);
                            assertThat(r.totalRevenue()).isEqualTo(15_000);
                            assertThat(r.salesCount()).isEqualTo(1);
                            assertThat(r.topProductName()).isEqualTo("Riz 25 kg");
                        });
                assertThat(repo.findTopStoreByUnits(SCHEMA, product, TODAY.minusDays(6), TODAY, null)).get()
                        .satisfies(t -> assertThat(t.unitsSold()).isEqualTo(1));
                assertThat(repo.findDailyMarginLast7(SCHEMA, product, TODAY, store)).singleElement()
                        .satisfies(m -> assertThat(m.dailyMargin()).isEqualTo(5_000));
            }
            // second run: the mixed tenant also invoiced the product (1 × 12 000 → margin 2 000), no store
            if (run == 0) {
                invoice("ISSUED", TODAY, 12_000, null, line(product, 1, 12_000));
            }
        }
        assertThat(pos.findDailyMarginLast7(SCHEMA, product, TODAY, null)).singleElement()
                .satisfies(m -> assertThat(m.dailyMargin()).isEqualTo(5_000));
        assertThat(mixed.findDailyMarginLast7(SCHEMA, product, TODAY, null)).singleElement()
                .satisfies(m -> assertThat(m.dailyMargin()).isEqualTo(7_000));
    }

    @Test
    @DisplayName("seller ranking: an invoice without creator is left out, no error")
    void invoiceWithoutCreator() {
        sale(TODAY_10H, 3000, "COMPLETED");
        UUID orphan = invoice("ISSUED", TODAY, 9_000, null, line(null, 1, 9_000));
        jdbc.update("UPDATE invoices SET created_by = NULL WHERE id = ?", orphan);

        assertThat(sellers(FixedRevenueSource.ALL)).singleElement().satisfies(r -> {
            assertThat(r.sellerId()).isEqualTo(seller);
            assertThat(r.totalRevenue()).isEqualTo(3000);
        });
        assertThat(dashboard(FixedRevenueSource.ALL).todayCA()).isEqualTo(12_000); // still revenue
    }

    // ── Story 20.14 — the store of documents ───────────────────────────────────────────────────

    @Test
    @DisplayName("20.14: an invoice of store B and its credit note count in B's figures (overview, store comparison, "
            + "store-filtered ranking and split); an invoice without store stays in the all-stores figures; the "
            + "tenant-wide revenue counts each document once")
    void documentsOfAStore() {
        UUID storeB = insertStore("Boutique B");
        sale(TODAY_10H, 3_000, "COMPLETED");                                         // store A
        UUID inB = invoiceIn(storeB, "ISSUED", TODAY, 10_000, line(product, 1, 10_000));
        creditNote(inB, 4_000, TODAY_10H, 1, 1, 4_000);                              // B: 10 000 − 4 000
        invoice("ISSUED", TODAY, 7_000, null, line(null, 1, 7_000));                 // made before V16: no store
        UUID bridgedSale = sale(TODAY_10H, 2_000, "COMPLETED");                      // A: + 2 000, its invoice in A
        invoiceIn(store, "PAID", TODAY, 2_000, bridgedSale, line(product, 1, 2_000));

        DashboardSummary d = dashboard(FixedRevenueSource.ALL);
        assertThat(d.todayCA()).isEqualTo(3_000 + 6_000 + 7_000 + 2_000);
        assertThat(d.todaySalesCount()).isEqualTo(4); // 2 sales + 2 invoices (the bridged one counted as its sale)
        assertThat(overview(d, store).todayCA()).isEqualTo(5_000);
        assertThat(overview(d, storeB).todayCA()).isEqualTo(6_000);

        JdbcProfitabilityRepository profitability = new JdbcProfitabilityRepository(jdbc, FixedRevenueSource.ALL);
        assertThat(profitability.findRawStoreByPeriod(SCHEMA, TODAY, TODAY))
                .filteredOn(r -> r.storeId().equals(storeB)).singleElement().satisfies(r -> {
                    assertThat(r.totalRevenue()).isEqualTo(6_000);
                    assertThat(r.salesCount()).isEqualTo(1);
                });
        assertThat(profitability.findRevenueSplit(SCHEMA, TODAY, TODAY, storeB).totalRevenue()).isEqualTo(6_000);
        assertThat(new JdbcSellerLeaderboardRepository(jdbc, FixedRevenueSource.ALL)
                .findRawSellers(TODAY, TODAY, storeB)).filteredOn(r -> seller.equals(r.sellerId())).singleElement()
                .satisfies(r -> {
                    assertThat(r.totalRevenue()).isEqualTo(6_000);
                    assertThat(r.salesCount()).isEqualTo(1);
                });

        // a tenant whose invoicing tables are below V16: documents in the all-stores figures only
        DashboardSummary old = dashboard(FixedRevenueSource.ALL_WITHOUT_STORES);
        assertThat(old.todayCA()).isEqualTo(d.todayCA());
        assertThat(overview(old, storeB).todayCA()).isZero();
        assertThat(overview(old, store).todayCA()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("20.14 FUS-NFR11: a store window on the source reaches idx_invoices_store_revenue_day")
    void storeWindowUsesTheV16Index() throws Exception {
        String plan = explain("SELECT COALESCE(SUM(r.total_amount), 0) FROM "
                + FixedRevenueSource.ALL.storeScoped().documents() + " r WHERE r.store_id = '" + store
                + "'::uuid AND r.occurred_at >= '2026-11-10T23:00:00Z'::timestamptz");
        // the store and the window are index conditions of the invoice branch (on empty tables the planner may read
        // sales through either of their indexes)
        assertThat(plan).contains("Index Scan using idx_invoices_store_revenue_day")
                .contains("Index Cond: ((store_id = '" + store + "'::uuid) AND");
    }

    @Test
    @DisplayName("20.14: clients export — purchases = sales + invoices (a bridged sale once), credit notes deducted "
            + "from the total, last purchase = latest sale or invoice; client lookups on the client indexes")
    void clientsExportReadsTheSource() throws Exception {
        UUID client = UUID.randomUUID();
        Timestamp t = Timestamp.from(Instant.parse("2025-01-01T00:00:00Z"));
        jdbc.update("INSERT INTO clients (id, archived, created_at, updated_at, name, phone) "
                + "VALUES (?, false, ?, ?, 'Awa Traoré', '+237699001122')", client, t, t);
        UUID s1 = sale(TODAY_10H.minusSeconds(86_400), 3_000, "COMPLETED");
        jdbc.update("UPDATE sales SET client_id = ? WHERE id = ?", client, s1);
        UUID auto = invoice("PAID", TODAY.minusDays(1), 3_000, s1, line(product, 1, 3_000)); // bridged: not again
        UUID inv = invoice("ISSUED", TODAY, 10_000, null, line(null, 1, 10_000));
        jdbc.update("UPDATE invoices SET client_id = ? WHERE id IN (?, ?)", client, auto, inv);
        creditNote(inv, 4_000, TODAY_10H.plusSeconds(3_600), 1, 1, 4_000);
        invoice("CANCELLED", TODAY, 50_000, null, line(null, 1, 50_000));

        var provider = new com.keevo.crm.contact.adapter.out.export.ClientsExportDatasetProvider(null,
                FixedRevenueSource.ALL);
        java.lang.reflect.Method from = provider.getClass().getDeclaredMethod("fromClause");
        from.setAccessible(true);
        String fromClause = (String) from.invoke(provider);
        Map<String, Object> row = jdbc.queryForMap("SELECT agg.sales_count, agg.total_spent, agg.last_purchase FROM "
                + fromClause + " WHERE c.id = ?", client);
        assertThat(((Number) row.get("sales_count")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("total_spent")).longValue()).isEqualTo(3_000 + 10_000 - 4_000);
        Timestamp issuedAt = jdbc.queryForObject("SELECT issued_at FROM invoices WHERE id = ?", Timestamp.class, inv);
        assertThat(((Timestamp) row.get("last_purchase")).toInstant()).isEqualTo(issuedAt.toInstant());

        String plan = explain("SELECT agg.total_spent FROM " + fromClause + " WHERE c.id = '" + client + "'::uuid");
        // client_id = c.id is pushed into the three branches; invoices and their credit notes are reached through
        // idx_invoices_client (on empty tables the planner may read sales through any of their indexes)
        assertThat(plan.split("client_id = c\\.id", -1)).hasSize(4);
        assertThat(plan).contains("Index Scan using idx_invoices_client").contains("idx_credit_notes_invoice");
    }

    @Test
    @DisplayName("20.14: hourly store activity (sales-trend job) — sales and invoices of each store at their instant, "
            + "credit notes 0, the same hour of the 3 previous days, one grouped query")
    void hourlyStoreActivity() {
        UUID storeB = insertStore("Boutique B");
        Instant hourEnd = Instant.parse("2026-11-11T12:00:00Z");
        sale(Instant.parse("2026-11-11T11:30:00Z"), 1_000, "COMPLETED");               // A, current hour
        sale(Instant.parse("2026-11-09T11:15:00Z"), 1_000, "COMPLETED");               // A, 2 days before
        sale(Instant.parse("2026-11-08T11:59:00Z"), 1_000, "COMPLETED");               // A, 3 days before
        sale(Instant.parse("2026-11-10T12:30:00Z"), 1_000, "COMPLETED");               // A, the hour after: out
        UUID now = invoiceIn(storeB, "ISSUED", TODAY, 5_000, line(null, 1, 5_000));
        issuedAt(now, "2026-11-11T11:10:00Z");                                          // B, current hour
        creditNote(now, 1_000, Instant.parse("2026-11-11T11:20:00Z"), 1, 1, 1_000);     // not a transaction
        UUID before = invoiceIn(storeB, "PAID", TODAY.minusDays(1), 5_000, line(null, 1, 5_000));
        issuedAt(before, "2026-11-10T11:30:00Z");                                       // B, 1 day before

        var activity = new com.keevo.messaging.notification.adapter.out.persistence.impl.JdbcStoreActivityRepository(
                jdbc, FixedRevenueSource.ALL).lastHour(hourEnd);

        assertThat(activity.get(store).current()).isEqualTo(1);
        assertThat(activity.get(store).previousDaysAverage()).isEqualTo(2 / 3.0);
        assertThat(activity.get(storeB).current()).isEqualTo(1);
        assertThat(activity.get(storeB).previousDaysAverage()).isEqualTo(1 / 3.0);
    }

    // ── consumers ───────────────────────────────────────────────────────────────

    private DashboardSummary dashboard(RevenueQueryPort source) {
        return new DashboardService(jdbc, CLOCK, source).execute(new GetDashboardSummaryQuery(SCHEMA));
    }

    private DashboardSummary.StoreOverviewEntry overview(DashboardSummary d, UUID storeId) {
        return d.storeOverviews().stream().filter(o -> o.storeId().equals(storeId.toString())).findFirst().orElseThrow();
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

    private UUID insertStore(String name) {
        UUID id = UUID.randomUUID();
        Timestamp t = Timestamp.from(Instant.parse("2025-01-01T00:00:00Z"));
        jdbc.update("INSERT INTO stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, ?, ?, true, ?, 'STORE')", id, t, t, name);
        return id;
    }

    private UUID insertProduct(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, sku, price, buy_price, transport_cost) VALUES (?, ?, ?, 0, 0, 0)",
                id, name, id.toString().substring(0, 8));
        return id;
    }

    private void employee(UUID storeId) {
        jdbc.update("INSERT INTO employees (id, user_id, store_id, first_name, last_name, status) "
                + "VALUES (?, ?, ?, 'Awa', 'Ngo', 'ACTIVE')", UUID.randomUUID(), UUID.randomUUID(), storeId);
    }

    /** A COMPLETED sale of {@code quantity} × {@code unitPrice} of one product in {@code storeId}. */
    private void saleOf(UUID storeId, Instant occurredAt, UUID productId, int quantity, int unitPrice) {
        UUID id = UUID.randomUUID();
        int total = quantity * unitPrice;
        jdbc.update("INSERT INTO sales (id, created_at, employee_id, occurred_at, payment_mode, status, store_id, "
                        + "total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, ?, 0)",
                id, Timestamp.from(occurredAt), seller, Timestamp.from(occurredAt), storeId, total);
        jdbc.update("INSERT INTO sale_items (sale_id, product_id, product_name, applied_unit_price, quantity, subtotal) "
                + "VALUES (?, ?, (SELECT name FROM products WHERE id = ?), ?, ?, ?)",
                id, productId, productId, unitPrice, quantity, total);
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

    /** Story 20.14 — an invoice made in {@code storeId}. */
    private UUID invoiceIn(UUID storeId, String status, LocalDate issueDate, long total, Line... lines) {
        return invoiceIn(storeId, status, issueDate, total, null, lines);
    }

    private UUID invoiceIn(UUID storeId, String status, LocalDate issueDate, long total, UUID sourceSale,
                           Line... lines) {
        UUID id = invoice(status, issueDate, total, sourceSale, lines);
        jdbc.update("UPDATE invoices SET store_id = ? WHERE id = ?", storeId, id);
        return id;
    }

    private void issuedAt(UUID invoice, String instant) {
        jdbc.update("UPDATE invoices SET issued_at = ? WHERE id = ?", Timestamp.from(Instant.parse(instant)), invoice);
    }

    private static String explain(String query) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET search_path TO \"" + SCHEMA + "\"");
            s.execute("SET enable_seqscan = off"); // tiny tables: make the planner show the usable index paths
            try (var rs = s.executeQuery("EXPLAIN " + query)) {
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
            }
        }
        return plan.toString();
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

    /** A manual credit note (source_event NULL), as IssueCreditNoteUseCase writes it. */
    private void creditNote(UUID invoice, long total, Instant issuedAt, int invoicePosition, int quantity,
                            long lineTotal) {
        creditNote(invoice, total, issuedAt, invoicePosition, quantity, lineTotal, null);
    }

    /** {@code sourceEvent} set = an automatic credit note of the POS bridge (sale cancelled / corrected). */
    private void creditNote(UUID invoice, long total, Instant issuedAt, int invoicePosition, int quantity,
                            long lineTotal, String sourceEvent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO credit_notes (id, number, invoice_id, reason, total_ht, total_vat, total_ttc, "
                        + "issued_at, source_event) VALUES (?, ?, ?, 'Retour', ?, 0, ?, ?, ?)",
                id, "AV-2026-" + String.format("%06d", ++numbers), invoice, total, total, Timestamp.from(issuedAt),
                sourceEvent);
        jdbc.update("INSERT INTO credit_note_items (credit_note_id, position, invoice_position, description, quantity, "
                        + "unit_price, vat_treatment, vat_rate, total_ht, total_vat, total_ttc) "
                        + "VALUES (?, 1, ?, 'Article', ?, ?, 'STANDARD', 0, ?, 0, ?)",
                id, invoicePosition, BigDecimal.valueOf(quantity), lineTotal / quantity, lineTotal, lineTotal);
    }
}
