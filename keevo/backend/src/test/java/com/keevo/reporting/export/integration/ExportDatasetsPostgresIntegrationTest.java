package com.keevo.reporting.export.integration;

import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.reporting.export.application.service.ExportDatasetRegistry;
import com.keevo.reporting.export.application.service.ExportJobRunner;
import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportJobStatus;
import com.keevo.reporting.export.domain.model.ExportRequest;
import com.keevo.reporting.export.domain.port.in.RequestExportUseCase;
import com.keevo.reporting.export.domain.port.out.ExportFileStorage;
import com.keevo.reporting.export.domain.port.out.ExportJobRepository;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportColumnType;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.application.port.export.ExportPage;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.export.ExportValues;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story v1s-18-2 — every registered export dataset on a real PostgreSQL 16, two provisioned
 * (Premium-trial) tenants seeded with rows in every table, across two stores and two periods.
 * Exports go through the real service (CSV zip and XLSX of all datasets at once, to stay under
 * the per-tenant hourly quota), and providers are paged directly for the keyset checks.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Export datasets on PostgreSQL (v1s-18-2)")
class ExportDatasetsPostgresIntegrationTest extends FullContextIntegrationTest {

    private static final Instant IN = Instant.parse("2026-03-15T10:00:00Z");   // 11:00 WAT
    private static final Instant OUT = Instant.parse("2026-01-10T10:00:00Z");
    private static final Instant FROM = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-03-31T23:59:59Z");

    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired RequestExportUseCase requestExport;
    @Autowired ExportDatasetRegistry registry;
    @Autowired AuditPort auditPort;
    @Autowired ExportJobRepository jobRepository;
    @Autowired ExportJobRunner jobRunner;
    @Autowired ExportFileStorage storage;

    private final List<String> filesToDelete = new ArrayList<>();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        filesToDelete.forEach(storage::delete);
    }

    // ── seeding ─────────────────────────────────────────────────────────────────

    /** Ids of one seeded tenant. Every text value carries the tenant tag. */
    record Seed(String schema, String tag, UUID owner, UUID employeeUser, UUID s1, UUID s2, UUID s0,
                UUID p1, UUID p2, UUID client) {
    }

    private Seed seed(String tag) {
        String schema = tx.execute(status -> tenantFactory.create()).getSchemaName();
        String q = "\"" + schema + "\".";
        UUID owner = insertUser("OWNER");
        UUID empUser = insertUser("EMPLOYEE");
        UUID s0 = jdbc.queryForObject("SELECT id FROM " + q + "stores LIMIT 1", UUID.class); // « Ma Boutique »
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "stores (id, name) VALUES (?, ?), (?, ?)", s1, tag + "-S1", s2, tag + "-S2");

        jdbc.update("INSERT INTO " + q + "employees (id, user_id, store_id, first_name, last_name, status) "
                + "VALUES (?, ?, ?, 'Awa', ?, 'ACTIVE'), (?, ?, ?, 'Paul', ?, 'INACTIVE')",
                UUID.randomUUID(), empUser, s1, tag + "-Emp", UUID.randomUUID(), UUID.randomUUID(), s2, tag + "-Old");
        // an older employee row of the same user: actor-name joins must take the newest, never duplicate rows
        jdbc.update("INSERT INTO " + q + "employees (id, user_id, store_id, first_name, last_name, status, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'Ancien', ?, 'INACTIVE', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z')",
                UUID.randomUUID(), empUser, s2, tag + "-Prev");

        UUID cat = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "categories (id, name) VALUES (?, ?)", cat, tag + "-Cat");
        jdbc.update("INSERT INTO " + q + "categories (id, name, parent_id, is_active) VALUES (?, ?, ?, FALSE)",
                UUID.randomUUID(), tag + "-SubCat", cat);

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "products (id, name, sku, category_id, price, buy_price, transport_cost, "
                + "minimum_threshold, archived, status, created_at) VALUES "
                + "(?, ?, 'SKU-1', ?, 1500, 1000, 50, 5, FALSE, 'ACTIVE', ?), "
                + "(?, ?, 'SKU-2', ?, 800, 500, 0, 0, TRUE, 'DRAFT', ?)",
                p1, tag + "-P1", cat, Timestamp.from(OUT), p2, tag + "-P2", cat, Timestamp.from(OUT));
        UUID sup = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "suppliers (id, name, phone, archived) VALUES (?, ?, '600000001', FALSE), "
                + "(?, ?, '600000002', TRUE)", sup, tag + "-Sup", UUID.randomUUID(), tag + "-OldSup");
        jdbc.update("INSERT INTO " + q + "product_suppliers (product_id, supplier_id) VALUES (?, ?)", p1, sup);
        UUID client = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "clients (id, name, phone, archived) VALUES (?, ?, '690000001', FALSE), "
                + "(?, ?, '690000002', TRUE)", client, tag + "-Client", UUID.randomUUID(), tag + "-OldClient");

        // P1@S2 falls under the product threshold (5); P2@S1 has its own threshold (7, product: 0)
        jdbc.update("INSERT INTO " + q + "stock_levels (id, product_id, store_id, quantity, minimum_threshold) VALUES "
                + "(?, ?, ?, 10, 0), (?, ?, ?, 3, 0), (?, ?, ?, 0, 7)",
                UUID.randomUUID(), p1, s1, UUID.randomUUID(), p1, s2, UUID.randomUUID(), p2, s1);

        // Event rows: one per (store, period); S1/IN and S2/IN share their instant (keyset ties).
        Object[][] events = {{s1, IN, 0}, {s1, OUT, 1}, {s2, IN, 2}, {s2, OUT, 3}};
        for (Object[] e : events) {
            UUID store = (UUID) e[0];
            Timestamp at = Timestamp.from((Instant) e[1]);
            int i = (int) e[2];
            UUID other = store.equals(s1) ? s2 : s0;
            jdbc.update("INSERT INTO " + q + "stock_movements (id, product_id, store_id, movement_type, quantity_before, "
                    + "quantity_change, quantity_after, actor_id, notes, occurred_at) VALUES (?, ?, ?, ?, 10, -1, 9, ?, ?, ?)",
                    UUID.randomUUID(), p1, store, i == 1 ? "SALE_CANCELLED" : "SALE", empUser, tag + " move", at);
            // S1→S2 (IN), S1←S2 (OUT, destination S1), S2→S0 (IN), S2←S0 (OUT)
            boolean outgoing = i % 2 == 0;
            jdbc.update("INSERT INTO " + q + "stock_transfers (id, source_store_id, destination_store_id, product_id, "
                    + "quantity, actor_id, occurred_at, status, notes) VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?)",
                    UUID.randomUUID(), outgoing ? store : other, outgoing ? other : store, p1, owner, at,
                    i == 3 ? "IN_TRANSIT" : "COMPLETED", tag + " transfer");
            UUID sale = UUID.randomUUID();
            String status = switch (i) { case 1 -> "CANCELLED"; case 2 -> "PENDING_VALIDATION"; default -> "COMPLETED"; };
            // the client also has a CANCELLED (S1/OUT) and a PENDING (S2/IN) sale: not counted as purchases
            // S2/IN: no occurred_at, the server date (created_at) is the sale date
            jdbc.update("INSERT INTO " + q + "sales (id, store_id, employee_id, client_id, total_amount, payment_mode, "
                    + "created_at, status, occurred_at, discount_amount, mobile_money_ref) "
                    + "VALUES (?, ?, ?, ?, 1500, ?, ?, ?, ?, 100, ?)",
                    sale, store, empUser, i <= 2 ? client : null, i == 1 ? "MOBILE_MONEY" : "CASH", at, status,
                    i == 2 ? null : at, i == 1 ? "MP2603.1234" : null);
            jdbc.update("INSERT INTO " + q + "sale_items (id, sale_id, product_id, product_name, applied_unit_price, "
                    + "quantity, subtotal, catalogue_unit_price) VALUES (?, ?, ?, ?, 1500, 1, 1500, 1600)",
                    UUID.randomUUID(), sale, p1, tag + "-P1");
            if (i == 0) {
                jdbc.update("INSERT INTO " + q + "sale_items (id, sale_id, product_id, product_name, applied_unit_price, "
                        + "quantity, subtotal, catalogue_unit_price) VALUES (?, ?, ?, ?, 800, 2, 1600, 800)",
                        UUID.randomUUID(), sale, p2, tag + "-P2");
            }
            jdbc.update("INSERT INTO " + q + "day_closures (id, store_id, actor_id, closed_at, closure_date, is_automatic, "
                    + "total_sales, total_revenue, cash_amount, momo_amount, top_product_name, top_product_qty) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 2, 3000, 2000, 1000, ?, 2)",
                    UUID.randomUUID(), store, i == 3 ? null : owner, at,
                    Date.valueOf(LocalDate.ofInstant((Instant) e[1], java.time.ZoneOffset.UTC)), i == 3,
                    tag + "-P1");
            UUID session = UUID.randomUUID();
            jdbc.update("INSERT INTO " + q + "inventory_sessions (id, store_id, scope, status, started_by, started_at, "
                    + "cancelled_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    session, store, i == 1 ? "PARTIAL" : "FULL", i == 0 ? "CANCELLED" : "VALIDATED", owner, at,
                    i == 0 ? at : null);
            jdbc.update("INSERT INTO " + q + "inventory_counts (id, session_id, product_id, product_name, theoretical, "
                    + "physical, counted_at, counted_by) VALUES (?, ?, ?, ?, 10, 8, ?, ?)",
                    UUID.randomUUID(), session, p1, tag + "-P1", at, empUser);
            if (i == 0) {
                jdbc.update("INSERT INTO " + q + "inventory_counts (id, session_id, product_id, product_name, "
                        + "variant_label, theoretical) VALUES (?, ?, ?, ?, 'Rouge', 4)",
                        UUID.randomUUID(), session, p2, tag + "-P2");
            }
            jdbc.update("INSERT INTO " + q + "reports (id, tenant_id, store_id, store_name, report_type, report_date, "
                    + "content, delivery_status, total_revenue, total_sales, is_automatic, actor_name, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 'SENT', 3000, 2, TRUE, 'Awa', ?)",
                    UUID.randomUUID(), schema, store, tag + "-store", i == 1 ? "WEEKLY" : "DAILY",
                    Date.valueOf(LocalDate.ofInstant((Instant) e[1], java.time.ZoneOffset.UTC)),
                    "Rapport " + tag, at);
            jdbc.update("INSERT INTO " + q + "audit_log (id, user_id, entity_type, entity_id, action, occurred_at, "
                    + "store_id, entity_label, source, value_after) VALUES (?, ?, 'Sale', ?, 'SALE_CREATED', ?, ?, ?, "
                    + "'SYNC', '{\"total\":1500}')",
                    UUID.randomUUID(), empUser, sale, at, store, "Vente de 1500 XAF " + tag);
        }
        jdbc.update("INSERT INTO " + q + "audit_log (id, user_id, entity_type, entity_id, action, occurred_at, store_id, "
                + "entity_label, source) VALUES (?, ?, 'Product', ?, 'PRODUCT_UPDATED', ?, ?, ?, 'ONLINE')",
                UUID.randomUUID(), owner, p1, Timestamp.from(IN), s1, tag + "-P1");
        return new Seed(schema, tag, owner, empUser, s1, s2, s0, p1, p2, client);
    }

    private UUID insertUser(String role) {
        UUID id = UUID.randomUUID();
        String phone = "+2378" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                + "password_hash, phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, ?)", id, phone, role);
        return id;
    }

    // ── export helpers ──────────────────────────────────────────────────────────

    private byte[] export(String schema, List<String> datasets, ExportFormat format, ExportFilter filter)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantContext.setCurrentTenant(schema);
        try {
            var outcome = requestExport.request(schema, UUID.randomUUID(),
                    new ExportRequest(datasets, format, filter), (ct, name) -> out);
            assertThat(outcome).isInstanceOf(RequestExportUseCase.Direct.class);
        } finally {
            TenantContext.clear();
        }
        return out.toByteArray();
    }

    /** dataset key → lines (header first) of a zip of CSVs. */
    private Map<String, List<String>> unzipCsv(byte[] zip) throws Exception {
        Map<String, List<String>> files = new LinkedHashMap<>();
        unzip(zip).forEach((key, csv) -> files.put(key, List.of(csv.split("\r\n"))));
        return files;
    }

    /**
     * dataset key → CSV text (BOM removed) of a zip of CSVs. Entries are named after the French
     * label (v1s-16-20), mapped back to the dataset key here.
     */
    private Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> keyBySlug = new LinkedHashMap<>();
        registry.all().forEach(p -> keyBySlug.put(ExportValues.slug(p.label()) + ".csv", p.key()));
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(csv).as(entry.getName()).startsWith("\uFEFF");
                assertThat(keyBySlug).as("zip entry " + entry.getName()).containsKey(entry.getName());
                files.put(keyBySlug.get(entry.getName()), csv.substring(1));
            }
        }
        return files;
    }

    private static String header(ExportDatasetProvider p) {
        return String.join(";", p.columns().stream().map(ExportColumn::label).toList());
    }

    private long auditCount(Seed s, ExportFilter f) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM \"" + s.schema() + "\".audit_log WHERE TRUE");
        List<Object> args = new ArrayList<>();
        if (f.storeId() != null) { sql.append(" AND store_id = ?"); args.add(f.storeId()); }
        if (f.from() != null) { sql.append(" AND occurred_at >= ?"); args.add(Timestamp.from(f.from())); }
        if (f.to() != null) { sql.append(" AND occurred_at <= ?"); args.add(Timestamp.from(f.to())); }
        return jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    }

    /** Expected rows per dataset for filters [none, store S1, period, store S1 + period]. */
    private static final Map<String, int[]> EXPECTED = Map.ofEntries(
            Map.entry("products", new int[]{2, 2, 2, 2}),          // reference, archived + draft included
            Map.entry("categories", new int[]{2, 2, 2, 2}),        // not store-scoped
            Map.entry("stock_levels", new int[]{3, 2, 3, 2}),
            Map.entry("stock_movements", new int[]{4, 2, 2, 1}),
            Map.entry("stock_transfers", new int[]{4, 2, 2, 1}),   // S1 as source or destination
            Map.entry("sales", new int[]{4, 2, 2, 1}),             // S2/IN dated by created_at
            Map.entry("sale_items", new int[]{5, 3, 3, 2}),
            Map.entry("day_closures", new int[]{4, 2, 2, 1}),
            Map.entry("inventory_sessions", new int[]{4, 2, 2, 1}),
            Map.entry("inventory_counts", new int[]{5, 3, 3, 2}),
            Map.entry("clients", new int[]{2, 2, 2, 2}),
            Map.entry("suppliers", new int[]{2, 2, 2, 2}),
            Map.entry("employees", new int[]{3, 1, 3, 1}),         // + the older row of the employee user
            Map.entry("stores", new int[]{3, 1, 3, 1}),            // + « Ma Boutique »
            Map.entry("reports", new int[]{4, 2, 2, 1}));

    private static List<ExportFilter> filters(Seed s) {
        return List.of(ExportFilter.none(), new ExportFilter(s.s1(), null, null),
                new ExportFilter(null, FROM, TO), new ExportFilter(s.s1(), FROM, TO));
    }

    // ── tests ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every dataset, CSV and XLSX, four filter combinations: headers = labels, filtered row counts, tenant-isolated")
    void everyDatasetCsvAndXlsx() throws Exception {
        Seed a = seed("A" + UUID.randomUUID().toString().substring(0, 8));
        Seed b = seed("B" + UUID.randomUUID().toString().substring(0, 8));
        List<ExportDatasetProvider> providers = registry.all();
        List<String> keys = providers.stream().map(ExportDatasetProvider::key).toList();
        Set<String> expectedKeys = new HashSet<>(EXPECTED.keySet());
        expectedKeys.add("audit_log");
        assertThat(keys).containsExactlyInAnyOrderElementsOf(expectedKeys);

        List<ExportFilter> filters = filters(a);
        for (int fi = 0; fi < filters.size(); fi++) {
            ExportFilter f = filters.get(fi);
            long auditExpected = auditCount(a, f); // read before this export audits itself

            Map<String, List<String>> csv = unzipCsv(export(a.schema(), keys, ExportFormat.CSV, f));
            assertThat(csv.keySet()).containsExactlyElementsOf(keys);
            for (ExportDatasetProvider p : providers) {
                List<String> lines = csv.get(p.key());
                assertThat(lines.get(0)).as(p.key() + " header").isEqualTo(header(p));
                long expected = p.key().equals("audit_log") ? auditExpected : EXPECTED.get(p.key())[fi];
                assertThat(lines).as(p.key() + " rows, filter #" + fi).hasSize((int) expected + 1);
                assertThat(String.join("\n", lines)).as(p.key() + " isolation").doesNotContain(b.tag());
            }

            auditExpected = auditCount(a, f);
            byte[] xlsx = export(a.schema(), keys, ExportFormat.XLSX, f);
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
                assertThat(wb.getNumberOfSheets()).isEqualTo(providers.size());
                for (int si = 0; si < providers.size(); si++) {
                    ExportDatasetProvider p = providers.get(si);
                    XSSFSheet sheet = wb.getSheetAt(si);
                    long expected = p.key().equals("audit_log") ? auditExpected : EXPECTED.get(p.key())[fi];
                    assertThat(sheet.getLastRowNum()).as(p.key() + " xlsx rows, filter #" + fi).isEqualTo((int) expected);
                    assertSheet(p, sheet, b.tag());
                }
            }
        }
    }

    /** Header = labels; AMOUNT_XAF numeric integers in XAF format; DATETIME in the WAT datetime format. */
    private static void assertSheet(ExportDatasetProvider p, XSSFSheet sheet, String foreignTag) {
        Row header = sheet.getRow(0);
        for (int c = 0; c < p.columns().size(); c++) {
            assertThat(header.getCell(c).getStringCellValue()).isEqualTo(p.columns().get(c).label());
        }
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            for (int c = 0; c < p.columns().size(); c++) {
                Cell cell = row.getCell(c);
                if (cell == null) continue;
                ExportColumnType type = p.columns().get(c).type();
                if (type == ExportColumnType.AMOUNT_XAF) {
                    assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
                    assertThat(cell.getNumericCellValue() % 1).as(p.key() + " integer XAF").isZero();
                    assertThat(cell.getCellStyle().getDataFormatString()).isEqualTo("#,##0 \"XAF\"");
                } else if (type == ExportColumnType.DATETIME) {
                    assertThat(cell.getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy hh:mm:ss");
                } else if (cell.getCellType() == CellType.STRING) {
                    assertThat(cell.getStringCellValue()).doesNotContain(foreignTag);
                }
            }
        }
    }

    @Test
    @DisplayName("content: French labels, archived / cancelled rows kept, integer XAF, WAT dates, store-scoped stock, actor names")
    void content() throws Exception {
        Seed a = seed("C" + UUID.randomUUID().toString().substring(0, 8));
        Map<String, List<String>> all = unzipCsv(export(a.schema(),
                List.of("products", "sales", "sale_items", "inventory_sessions", "employees", "clients",
                        "stock_movements", "stock_transfers", "stock_levels", "day_closures", "reports",
                        "inventory_counts"),
                ExportFormat.CSV, ExportFilter.none()));

        String products = String.join("\n", all.get("products"));
        assertThat(products).contains("SKU-1;" + a.tag() + "-P1;" + a.tag() + "-Cat;Actif;1500;1000;50;5;13;"
                + a.tag() + "-Sup;Non;10/01/2026 11:00:00");
        assertThat(products).contains("SKU-2;" + a.tag() + "-P2;" + a.tag() + "-Cat;Brouillon;800;500;0;0;0;;Oui");

        String sales = String.join("\n", all.get("sales"));
        assertThat(sales).contains("Vente du 15/03/2026 à 11:00:00 — " + a.tag() + "-S1;15/03/2026 11:00:00;"
                + a.tag() + "-S1;Awa " + a.tag() + "-Emp;" + a.tag()
                + "-Client;Validée;Espèces;;3;100;1500");
        assertThat(sales).contains(";Annulée;Mobile Money;MP2603.1234;1;100;1500",
                ";En attente de validation;Espèces;;1;100;1500");

        assertThat(String.join("\n", all.get("sale_items")))
                .contains(";SKU-2;" + a.tag() + "-P2;2;800;800;1600")
                // same readable reference as the sale: the two files can be matched
                .contains("Vente du 15/03/2026 à 11:00:00 — " + a.tag() + "-S1;15/03/2026 11:00:00;");
        assertThat(String.join("\n", all.get("inventory_sessions")))
                .contains(";Annulé;Propriétaire;", ";Complet;", ";Partiel;")
                .contains(";1;1;-2"); // S1/IN: one counted line out of two, gap 8 − 10
        assertThat(String.join("\n", all.get("inventory_counts")))
                .contains(a.tag() + "-P2;Rouge;4;;;;"); // not counted: empty count, gap, date, author
        String empPhone = jdbc.queryForObject("SELECT phone_number FROM public.users WHERE id = ?", String.class,
                a.employeeUser());
        assertThat(String.join("\n", all.get("employees"))).contains(";Inactif;")
                .contains("\nAwa;" + a.tag() + "-Emp;" + empPhone + ";" + a.tag() + "-S1;Actif;");
        assertThat(String.join("\n", all.get("clients")))
                .contains(a.tag() + "-Client;690000001;;;1;1500;15/03/2026 11:00:00;Non")
                .contains(a.tag() + "-OldClient;690000002;;;0;0;;Oui");
        assertThat(String.join("\n", all.get("stock_movements")))
                .contains(";Vente;10;-1;9;Awa " + a.tag() + "-Emp;", ";Annulation de vente;");
        assertThat(String.join("\n", all.get("stock_transfers")))
                .contains(";" + a.tag() + "-S1;" + a.tag() + "-S2;SKU-1;", ";En transit;Propriétaire;");
        assertThat(String.join("\n", all.get("stock_levels")))
                .contains(a.tag() + "-S1;SKU-1;" + a.tag() + "-P1;" + a.tag() + "-Cat;10;5;OK;1000;10000;Non")
                .contains(a.tag() + "-S2;SKU-1;" + a.tag() + "-P1;" + a.tag() + "-Cat;3;5;Stock bas;1000;3000;Non")
                .contains(";0;7;Rupture;500;0;Oui"); // the store level's own threshold wins
        assertThat(String.join("\n", all.get("day_closures")))
                .contains("15/03/2026;15/03/2026 11:00:00;" + a.tag() + "-S1;Non;Propriétaire;2;3000;2000;1000;0;0;")
                .contains(";Oui;;2;3000;"); // automatic closure without actor
        assertThat(String.join("\n", all.get("reports")))
                .contains(";Fin de journée;" + a.tag() + "-S1;3000;2;Oui;Awa;Envoyé;", ";Hebdomadaire;");

        // store filter narrows the product stock column only
        List<String> s1Products = unzipCsv(export(a.schema(), List.of("products", "stores"), ExportFormat.CSV,
                new ExportFilter(a.s1(), FROM, TO))).get("products");
        assertThat(String.join("\n", s1Products)).contains("Actif;1500;1000;50;5;10;");
    }

    @Test
    @DisplayName("audit_log with employee / action / object type / search criteria + store + period = GET /api/v1/audit rows, newest first")
    void auditCriteria() throws Exception {
        Seed a = seed("D" + UUID.randomUUID().toString().substring(0, 8));
        Map<String, String> criteria = Map.of("userId", a.employeeUser().toString(), "action", "SALE_CREATED",
                "entityType", "Sale", "q", "vente de");
        for (ExportFilter f : List.of(new ExportFilter(a.s1(), FROM, TO, criteria),
                new ExportFilter(null, null, null, criteria),
                new ExportFilter(null, null, null, Map.of("q", a.tag() + "-P1")),
                new ExportFilter(null, null, null, Map.of("entityId", a.p1().toString())))) {
            AuditPort.AuditQuery query = new AuditPort.AuditQuery(f.from(), f.to(), f.storeId(),
                    f.criterion("userId") != null ? UUID.fromString(f.criterion("userId")) : null,
                    f.criterion("action"), f.criterion("entityType"),
                    f.criterion("entityId") != null ? UUID.fromString(f.criterion("entityId")) : null, f.criterion("q"));
            TenantContext.setCurrentTenant(a.schema());
            List<String> expectedDates;
            try {
                // no id in the file (v1s-16-20): rows are matched on their WAT date, newest first
                expectedDates = auditPort.search(query, null, 0, 100).entries().stream()
                        .map(e -> java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                                .format(e.occurredAt().atZone(ExportValues.WAT))).toList();
                assertThat(auditPort.count(query)).as("count = search rows").isEqualTo(expectedDates.size());
            } finally {
                TenantContext.clear();
            }
            assertThat(expectedDates).isNotEmpty();
            String csv = new String(export(a.schema(), List.of("audit_log"), ExportFormat.CSV, f),
                    StandardCharsets.UTF_8);
            List<String> lines = List.of(csv.substring(1).split("\r\n"));
            assertThat(lines.subList(1, lines.size()).stream().map(l -> l.substring(0, 19)).toList())
                    .containsExactlyElementsOf(expectedDates);
        }
        String one = new String(export(a.schema(), List.of("audit_log"), ExportFormat.CSV,
                new ExportFilter(a.s1(), FROM, TO, criteria)), StandardCharsets.UTF_8);
        // French action (unknown code humanized) and object type, readable changes, no id / device / JSON
        assertThat(one).contains("15/03/2026 11:00:00;Awa " + a.tag() + "-Emp;Employé;Sale created;Vente;")
                .contains(";Vente de 1500 XAF " + a.tag() + ";" + a.tag() + "-S1;Synchronisé;Total : 1 500;")
                .doesNotContain("SALE_CREATED", "{");

        // criteria on a dataset that does not accept them → 400 VALIDATION_ERROR
        assertThatThrownBy(() -> export(a.schema(), List.of("products"), ExportFormat.CSV,
                new ExportFilter(null, null, null, Map.of("action", "X"))))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("keyset with page limit 1 returns every row exactly once, in order, for every dataset (ties included)")
    void keysetLimitOne() {
        Seed a = seed("E" + UUID.randomUUID().toString().substring(0, 8));
        TenantContext.setCurrentTenant(a.schema());
        try {
            for (ExportFilter f : List.of(ExportFilter.none(), new ExportFilter(a.s1(), FROM, TO))) {
                for (ExportDatasetProvider p : registry.all()) {
                    String as = p.key() + (f.storeId() != null ? " (store + period)" : "");
                    ExportPage full = p.readPage(f, null, 1000);
                    assertThat(full.nextCursor()).isNull();
                    List<List<Object>> paged = new ArrayList<>();
                    String cursor = null;
                    int guard = 0;
                    do {
                        ExportPage page = p.readPage(f, cursor, 1);
                        assertThat(page.rows().size()).isLessThanOrEqualTo(1);
                        paged.addAll(page.rows());
                        cursor = page.nextCursor();
                    } while (cursor != null && ++guard < 10_000);
                    assertThat(paged).as(as).containsExactlyElementsOf(full.rows());
                    assertThat(paged).as(as).isNotEmpty();
                    assertThat(p.count(f)).as(as + " count").isEqualTo(full.rows().size());
                    assertThatThrownBy(() -> p.readPage(f, "not-a-cursor!", 1))
                            .as(as).isInstanceOf(IllegalArgumentException.class);
                }
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("V16: criteria column in public and tenant export_jobs; a background job round-trips its criteria and applies them")
    void backgroundJobCriteria() throws Exception {
        Seed a = seed("F" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'export_jobs' AND column_name = 'criteria'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'export_jobs' AND column_name = 'criteria'", Integer.class, a.schema())).isEqualTo(1);

        Map<String, String> criteria = Map.of("action", "PRODUCT_UPDATED", "entityType", "Product");
        TenantContext.setCurrentTenant(a.schema());
        ExportJob job = jobRepository.save(ExportJob.pending(UUID.randomUUID(), a.owner(), ExportFormat.CSV,
                List.of("audit_log"), new ExportFilter(a.s1(), FROM, TO, criteria), "keevo-journal-d-audit.csv",
                "text/csv; charset=UTF-8", Instant.now()));
        TenantContext.clear();
        assertThat(jdbc.queryForObject("SELECT criteria FROM \"" + a.schema() + "\".export_jobs WHERE id = ?",
                String.class, job.getId())).isEqualTo("{\"action\":\"PRODUCT_UPDATED\",\"entityType\":\"Product\"}");

        jobRunner.run(a.schema(), job.getId());

        TenantContext.setCurrentTenant(a.schema());
        ExportJob ready = jobRepository.findById(job.getId()).orElseThrow();
        TenantContext.clear();
        filesToDelete.add(ready.getStoragePath());
        assertThat(ready.getStatus()).isEqualTo(ExportJobStatus.READY);
        assertThat(ready.getFilter().criteria()).isEqualTo(criteria);
        assertThat(ready.getRowCount()).isEqualTo(1);
        try (InputStream in = storage.read(ready.getStoragePath())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).contains(";Produit modifié;Produit;")
                    .doesNotContain("Sale created", "PRODUCT_UPDATED");
        }
    }

    // ── v1s-16-20 guard: no technical data in any export ────────────────────────

    @Test
    @DisplayName("v1s-16-20 guard: no header, file name or cell of any dataset (CSV + XLSX) holds a UUID, a SNAKE_CASE code or JSON")
    void noTechnicalDataInAnyExport() throws Exception {
        Seed a = seed("G" + UUID.randomUUID().toString().substring(0, 8));
        String q = "\"" + a.schema() + "\".";
        // rows the guard must see rendered readably: a warehouse, an inventory note, an audit row with
        // JSON before/after (ids, nested map, codes) from a synced device + its metadata
        jdbc.update("INSERT INTO " + q + "stores (id, name, type) VALUES (?, ?, 'WAREHOUSE')",
                UUID.randomUUID(), a.tag() + "-Depot");
        jdbc.update("INSERT INTO " + q + "stock_movements (id, product_id, store_id, movement_type, quantity_before, "
                + "quantity_change, quantity_after, actor_id, notes, occurred_at) VALUES (?, ?, ?, 'ADJUSTMENT', 10, -2, 8, "
                + "?, ?, ?)", UUID.randomUUID(), a.p1(), a.s1(), a.owner(), "INVENTORY:" + UUID.randomUUID(),
                Timestamp.from(IN));
        jdbc.update("INSERT INTO " + q + "audit_log (id, user_id, entity_type, entity_id, action, occurred_at, store_id, "
                + "entity_label, source, device_id, value_before, value_after, metadata) VALUES (?, ?, 'PriceOverride', ?, "
                + "'PRICE_OVERRIDDEN', ?, ?, ?, 'SYNC', 'android-3f2a9c1d8e7b6a5f', ?, ?, CAST(? AS jsonb))",
                UUID.randomUUID(), a.employeeUser(), a.p1(), Timestamp.from(IN), a.s1(), a.tag() + "-P1",
                "{\"price\":1000,\"productId\":\"" + a.p1() + "\",\"status\":\"PENDING_VALIDATION\"}",
                "{\"price\":1500,\"criteria\":{\"q\":\"x\"},\"movementType\":\"STOCK_ENTRY\",\"tags\":[1,2]}",
                "{\"storeId\":\"" + a.s1() + "\",\"notes\":\"INVENTORY:" + UUID.randomUUID()
                        + "\",\"isAutomatic\":true,\"actorLabel\":\"Awa\"}");
        jdbc.update("INSERT INTO " + q + "audit_log (id, user_id, entity_type, entity_id, action, occurred_at, "
                + "entity_label, source, value_after) VALUES (?, ?, 'Gadget', ?, 'FOO_BAR', ?, ?, 'SYSTEM', '{oops')",
                UUID.randomUUID(), a.owner(), UUID.randomUUID(), Timestamp.from(IN), UUID.randomUUID().toString());

        List<ExportDatasetProvider> providers = registry.all();
        List<String> keys = providers.stream().map(ExportDatasetProvider::key).toList();
        List<String> violations = new ArrayList<>();

        // CSV (zip of every dataset): entry names, headers and cells
        Map<String, String> csv = unzip(export(a.schema(), keys, ExportFormat.CSV, ExportFilter.none()));
        assertThat(csv.keySet()).containsExactlyInAnyOrderElementsOf(keys);
        CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter(';').build();
        csv.forEach((key, text) -> {
            try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
                List<CSVRecord> records = parser.getRecords();
                assertThat(records.size()).as(key + " has rows").isGreaterThan(1);
                for (CSVRecord rec : records) {
                    for (int c = 0; c < rec.size(); c++) {
                        check(violations, "CSV " + key + " row " + rec.getRecordNumber() + " col " + c, rec.get(c));
                    }
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        String auditCsv = csv.get("audit_log");
        assertThat(auditCsv).contains(";Prix modifié en caisse;Prix de vente;" + a.tag() + "-P1;" + a.tag()
                        + "-S1;Synchronisé;")
                .contains("Prix : 1 000 XAF → 1 500 XAF")
                .contains("Motif : Ajustement d'inventaire ; Déclenchement : Automatique")
                .contains(";Foo bar;Gadget;;");
        assertThat(csv.get("stores")).contains(a.tag() + "-Depot;Entrepôt;");
        assertThat(csv.get("stock_movements")).contains(";Ajustement d'inventaire");

        // XLSX: sheet names, headers and every string cell
        byte[] xlsx = export(a.schema(), keys, ExportFormat.XLSX, ExportFilter.none());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                XSSFSheet sheet = wb.getSheetAt(si);
                check(violations, "XLSX sheet name", sheet.getSheetName());
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING) {
                            check(violations, "XLSX " + sheet.getSheetName() + " " + cell.getAddress(),
                                    cell.getStringCellValue());
                        }
                    }
                }
            }
        }
        // file names (one request per dataset would burn the hourly quota): the slug of each label
        for (ExportDatasetProvider p : providers) {
            assertThat(ExportValues.slug(p.label())).as(p.key() + " file name part").matches("[a-z0-9-]+");
        }
        assertThat(violations).as("technical data found in exports").isEmpty();
    }

    @Test
    @DisplayName("v1s-16-20: PDF models read hidden trailing ids of the real providers; PDFs hold no id or code")
    void pdfHiddenIdsAndNoTechnicalText() throws Exception {
        Seed a = seed("H" + UUID.randomUUID().toString().substring(0, 8));
        Map<String, ExportDatasetProvider> byKey = new LinkedHashMap<>();
        registry.all().forEach(p -> byKey.put(p.key(), p));
        Map<String, String> idSql = Map.of(
                "stock_levels", "SELECT CAST(store_id AS text) FROM \"" + a.schema() + "\".stock_levels",
                "inventory_counts", "SELECT CAST(id AS text) FROM \"" + a.schema() + "\".inventory_sessions",
                "day_closures", "SELECT CAST(id AS text) FROM \"" + a.schema() + "\".day_closures");
        TenantContext.setCurrentTenant(a.schema());
        try {
            for (Map.Entry<String, String> e : idSql.entrySet()) {
                ExportDatasetProvider p = byKey.get(e.getKey());
                assertThat(p.internalKeys()).as(e.getKey()).hasSize(1);
                Set<String> expectedIds = new HashSet<>(jdbc.queryForList(e.getValue(), String.class));
                List<List<Object>> rows = p.readPage(ExportFilter.none(), null, 1000).rows();
                assertThat(rows).as(e.getKey()).isNotEmpty();
                for (List<Object> row : rows) {
                    assertThat(row).as(e.getKey() + " row width").hasSize(p.columns().size() + 1);
                    assertThat(String.valueOf(row.get(p.columns().size()))).as(e.getKey() + " hidden id")
                            .isIn(expectedIds);
                }
            }
        } finally {
            TenantContext.clear();
        }

        List<String> violations = new ArrayList<>();
        for (String key : List.of("stock_levels", "inventory_counts", "day_closures", "sales")) {
            byte[] pdf = export(a.schema(), List.of(key), ExportFormat.PDF, ExportFilter.none());
            String text;
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                text = new PDFTextStripper().getText(doc);
            }
            assertThat(text).as(key + " PDF text").isNotBlank();
            for (String line : text.split("\\R")) {
                check(violations, "PDF " + key, line);
            }
        }
        assertThat(violations).as("technical data found in PDFs").isEmpty();
    }

    private static void check(List<String> violations, String where, String value) {
        if (ExportValues.isTechnical(value)) {
            violations.add(where + ": " + value);
        }
    }
}
