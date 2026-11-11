package com.keevo.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefSourcePort;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 24.4 — documents, payments and expenses imported end to end through the super-admin API and the production
 * beans, from a synthetic Bref database (structure of {@code migration/bref-source-schema.sql}, random ids and phones,
 * no real data): a quote, paid / partial-overdue / cancelled / deleted / anomalous sales, instalments, two shops,
 * expenses. Checks the rows, the collection states, the seller header and settings, the old links, the records, the
 * sums per store and month against the source, a re-run, a partial re-run, the export source and a blocked owner.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("24.4 — Bref import of documents, payments and expenses")
class BrefSalesImportFullContextTest extends FullContextIntegrationTest {

    private static final String BREF_DB = "bref_src_it244";
    private static final Path EXPORT_DIR;

    static {
        SharedPostgresContainer.freshDatabase(BREF_DB);
        try {
            EXPORT_DIR = Files.createTempDirectory("bref-export-it244");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void brefSource(DynamicPropertyRegistry registry) {
        registry.add("keevo.migration.bref.jdbc.url", () -> SharedPostgresContainer.jdbcUrl(BREF_DB));
        registry.add("keevo.migration.bref.jdbc.username", SharedPostgresContainer::username);
        registry.add("keevo.migration.bref.jdbc.password", SharedPostgresContainer::password);
        registry.add("keevo.migration.bref.export-dir", EXPORT_DIR::toString);
    }

    private static JdbcTemplate bref;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired List<BrefSourcePort> sources;
    @Autowired InvoiceRepository invoices;

    private final UUID adminId = UUID.randomUUID();

    @BeforeAll
    static void brefSchema() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource(SharedPostgresContainer.jdbcUrl(BREF_DB),
                SharedPostgresContainer.username(), SharedPostgresContainer.password());
        bref = new JdbcTemplate(ds);
        bref.execute(new ClassPathResource("migration/bref-source-schema.sql").getContentAsString(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── fixtures (synthetic Bref rows) ───────────────────────────────────────

    private static final Instant T0 = Instant.parse("2026-03-01T08:00:00.123Z");

    private static String phone() {
        return "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static String tag() {
        return Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36);
    }

    private static LocalDateTime utc(Instant at) {
        return at == null ? null : LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static UUID owner() {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO \"user\" (id, phone, role, \"isActive\", \"isVerified\", \"createdAt\", \"updatedAt\") "
                + "VALUES (?, ?, 'owner', true, true, ?, ?)", id, phone(), utc(T0), utc(T0));
        return id;
    }

    private static UUID shop(UUID owner, String name, String category, Instant createdAt) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO shop (id, \"userId\", name, address, category, phone, niu, rccm, country, "
                        + "\"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, '699000111', 'M0123', 'RC/DLA/1', "
                        + "'CM', ?, ?)",
                id, owner, name, "Adresse " + name, category, utc(createdAt), utc(createdAt));
        return id;
    }

    private static void product(String shopId, String name, String key, String reference) {
        bref.update("INSERT INTO product (id, \"shopId\", name, \"nameKey\", \"productKey\", reference, \"unitPrice\", "
                        + "\"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, 1000, ?, ?)",
                UUID.randomUUID(), shopId, name, name, key, reference, utc(T0), utc(T0));
    }

    private static void customer(UUID shop, String phone) {
        bref.update("INSERT INTO customer (id, \"shopId\", name, phone, \"createdAt\", \"updatedAt\") "
                + "VALUES (?, ?, 'Client Test', ?, ?, ?)", UUID.randomUUID(), shop, phone, utc(T0), utc(T0));
    }

    /** One Bref invoice row (amounts as decimal text). */
    private static UUID invoice(UUID id, UUID owner, String shopId, String type, String status, String subtotal,
                                String tax, String discount, String delivery, String total, String initial,
                                String date, String customerPhone, UUID token, Instant createdAt, Instant deletedAt) {
        bref.update("INSERT INTO invoice (id, \"userId\", \"shopId\", \"customerName\", \"customerPhone\", subtotal, "
                        + "tax, discount, \"deliveryFee\", total, status, \"paymentMethod\", \"initialPayment\", date, "
                        + "\"publicToken\", \"publicTokenExpiresAt\", type, \"createdAt\", \"updatedAt\", "
                        + "\"deletedAt\", \"cancelledAt\", notes) VALUES (?, ?, ?, 'Client Test', ?, ?::numeric, "
                        + "?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::invoice_status_enum, 'mobile_money', "
                        + "?::numeric, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, owner.toString(), shopId, customerPhone, subtotal, tax, discount, delivery, total, status, initial,
                date, token, token == null ? null : utc(createdAt.plusSeconds(90L * 86400)), type, utc(createdAt),
                utc(createdAt.plusSeconds(60)), utc(deletedAt), "cancelled".equals(status)
                        ? utc(createdAt.plusSeconds(30)) : null, "Note libre " + type);
        return id;
    }

    private static void item(UUID invoice, String name, String reference, String quantity, String unit,
                             String total) {
        bref.update("INSERT INTO invoice_item (id, \"invoiceId\", reference, name, quantity, \"unitPrice\", total) "
                        + "VALUES (?, ?, ?, ?, ?::numeric, ?::numeric, ?::numeric)", UUID.randomUUID(), invoice,
                reference, name, quantity, unit, total);
    }

    private static void payment(UUID invoice, String amount, String due, boolean paid, String paidDate) {
        bref.update("INSERT INTO invoice_payment (id, \"invoiceId\", amount, \"dueDate\", \"isPaid\", \"paidDate\", "
                        + "\"createdAt\", \"updatedAt\") VALUES (?, ?, ?::numeric, ?, ?, ?, ?, ?)", UUID.randomUUID(),
                invoice, amount, due, paid, paidDate, utc(T0), utc(T0));
    }

    private static UUID expense(UUID owner, UUID shop, String amount, String category, String date, String method,
                                Instant deletedAt) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO expense (id, \"ownerId\", \"shopId\", \"createdById\", amount, category, description, "
                        + "\"expenseDate\", \"paymentMethod\", \"createdAt\", \"updatedAt\", \"deletedAt\") VALUES "
                        + "(?, ?, ?, ?, ?::numeric, ?, 'Dépense test', ?::date, ?, ?, ?, ?)",
                id, owner.toString(), shop.toString(), owner.toString(), amount, category, date, method, utc(T0),
                utc(T0), utc(deletedAt));
        return id;
    }

    private MvcResult importOwner(UUID owner, String source, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/migration/bref/owners/" + owner + "/imports")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"" + source + "\"}")).andReturn();
    }

    private String adminToken() {
        return TestAccess.token(jdbc, jwtTokenProvider, adminId, "public", "SUPER_ADMIN");
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("quotes then invoices, lines, payments, states, settings, links, expenses; sums = source; re-run, partial re-run, export = database")
    void documentsPaymentsExpenses() throws Exception {
        UUID owner = owner();
        UUID shopA = shop(owner, "Electro " + tag(), "electronics", T0);
        UUID shopB = shop(owner, "Epicerie " + tag(), "food", T0.plusSeconds(100));
        String a = shopA.toString();
        String bUpper = shopB.toString().toUpperCase(java.util.Locale.ROOT); // Bref keeps shopId as text
        product(a, "Riz parfumé 5 kg", "nm:riz parfume 5 kg", null);
        product(bUpper, "Savon", "bc:61112345", "61112345");
        String clientPhone = phone();
        customer(shopA, clientPhone);

        UUID quote = invoice(UUID.randomUUID(), owner, a, "quote", "pending", "6000", "0", "0", "0", "6000", "0",
                "2026-03-01", null, null, T0, null);
        item(quote, "Riz parfumé 5 kg", "REF-Q1", "1", "6000", "6000");
        UUID tokenS1 = UUID.randomUUID();
        UUID s1 = invoice(UUID.randomUUID(), owner, a, "sale", "paid", "25000", "0", "0", "0", "25000", "25000",
                "2026-03-02", clientPhone, tokenS1, T0.plusSeconds(1), null);
        item(s1, "riz  PARFUMÉ 5 kg", "REF-AB12", "2", "12500", "25000");
        UUID s2 = invoice(UUID.randomUUID(), owner, bUpper, "sale", "partial", "10000", "1925", "0", "0", "11925",
                "2000", "2026-03-03", null, null, T0.plusSeconds(2), null);
        item(s2, "Savon", "61112345", "2", "2500", "5000");
        item(s2, "Bougie", "REF-B2", "1", "3000", "3000");
        item(s2, "Allumettes", "REF-B3", "1", "2000", "2000");
        payment(s2, "3000", "2026-03-05", true, "2026-03-05");
        payment(s2, "6925", "2026-03-10", false, null);
        UUID s3 = invoice(UUID.randomUUID(), owner, a, "sale", "cancelled", "4000", "0", "0", "0", "4000", "500",
                "2026-03-04", null, UUID.randomUUID(), T0.plusSeconds(3), null);
        item(s3, "Article annulé", "REF-C", "1", "4000", "4000");
        UUID s4 = invoice(UUID.randomUUID(), owner, bUpper, "sale", "paid", "700", "0", "0", "0", "700", "700",
                "2026-03-05", null, UUID.randomUUID(), T0.plusSeconds(4), T0.plusSeconds(50));
        item(s4, "Supprimé", "REF-D", "1", "700", "700");
        UUID s5 = invoice(UUID.randomUUID(), owner, a, "sale", "pending", "1000", "0", "0", "0", "1500.50", "0",
                "2026-04-02", null, null, T0.plusSeconds(5), null);
        item(s5, "Anomalie", "REF-E", "1.5", "666.67", "1000");
        UUID s6 = invoice(UUID.randomUUID(), owner, bUpper, "sale", "pending", "800", "0", "0", "0", "800", "0",
                "2026-04-03", null, null, T0.plusSeconds(6), null);
        item(s6, "Encaissé", "REF-F", "1", "800", "800");
        payment(s6, "800", "2026-04-04", true, "2026-04-04");
        UUID s7 = invoice(UUID.randomUUID(), owner, a, "sale", "paid", "1000", "0", "0", "0", "1000", "300",
                "2026-04-04", null, null, T0.plusSeconds(7), null);
        item(s7, "Payé sans tout", "REF-G", "1", "1000", "1000");
        UUID s8 = invoice(UUID.fromString(s1.toString().substring(0, 8) + "-1111-4111-8111-111111111111"), owner, a,
                "sale", "paid", "300", "0", "0", "0", "300", "300", "2026-04-05", null, null, T0.plusSeconds(8),
                null);
        item(s8, "Même préfixe", "REF-H", "1", "300", "300");
        UUID e1 = expense(owner, shopA, "15000.50", "rent", "2026-03-05", "cash", null);
        UUID e2 = expense(owner, shopB, "20000", "goods_purchase", "2026-04-01", "mobile_money", null);
        UUID e3 = expense(owner, shopA, "100", "transport", "2026-04-02", null, T0.plusSeconds(9));
        UUID e4 = expense(owner, shopB, "250", "loisirs", "2026-04-03", "cheque", null);
        String token = adminToken();

        // ── first import
        MvcResult first = importOwner(owner, "DATABASE", token);
        assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode batch = data(first);
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        JsonNode counts = batch.path("counts");
        assertThat(counts.path("quotesCreated").asInt()).isEqualTo(1);
        assertThat(counts.path("invoicesCreated").asInt()).isEqualTo(8);
        assertThat(counts.path("invoiceLinesCreated").asInt()).isEqualTo(11);
        // acomptes S1, S2, S3, S4, S7, S8 + instalments S2 ×2, S6
        assertThat(counts.path("paymentsCreated").asInt()).isEqualTo(9);
        assertThat(counts.path("expensesCreated").asInt()).isEqualTo(4);
        assertThat(batch.path("notes").toString()).contains("DOCUMENT_TOTAL_MISMATCH", "DOCUMENT_AMOUNT_NOT_INTEGER",
                "DOCUMENT_PAID_NOT_COLLECTED", "DOCUMENT_STATE_DIFFERS", "DOCUMENT_DELETED",
                "DOCUMENT_NUMBER_SUFFIXED", "EXPENSE_CATEGORY_UNKNOWN", "EXPENSE_METHOD_UNKNOWN", "EXPENSE_DELETED");
        UUID tenantId = UUID.fromString(batch.path("tenantId").asText());
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class,
                tenantId);
        String inv = "\"" + schema + "\".invoices";

        Map<UUID, Map<String, Object>> rows = new HashMap<>();
        jdbc.queryForList("SELECT * FROM " + inv).forEach(r -> rows.put((UUID) r.get("id"), r));
        assertThat(rows).hasSize(9);
        assertThat(rows.get(quote)).containsEntry("kind", "QUOTE").containsEntry("status", "DRAFT")
                .containsEntry("number", null).containsEntry("payment_state", null)
                .containsEntry("legacy_notes", "Note libre quote");
        assertThat(rows.get(s1)).containsEntry("kind", "INVOICE").containsEntry("status", "PAID")
                .containsEntry("payment_state", "PAID").containsEntry("store_id", shopA)
                .containsEntry("legacy_public_token", tokenS1).containsEntry("payment_method", "Mobile Money")
                .containsEntry("number", "BRF-" + s1.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT))
                .containsEntry("created_by", owner);
        assertThat((String) rows.get(s1).get("seller_legal_name")).startsWith("Electro ");
        UUID client = (UUID) rows.get(s1).get("client_id");
        assertThat(client).isNotNull();
        assertThat(jdbc.queryForObject("SELECT store_id FROM \"" + schema + "\".clients WHERE id = ?", UUID.class,
                client)).isEqualTo(shopA);
        assertThat(rows.get(s2)).containsEntry("status", "ISSUED").containsEntry("payment_state", "OVERDUE")
                .containsEntry("store_id", shopB).containsEntry("vat_registered", true)
                .containsEntry("total_vat", 1925L).containsEntry("total_ttc", 11925L);
        assertThat((BigDecimal) rows.get(s2).get("vat_rate")).isEqualByComparingTo("19.25");
        assertThat((String) rows.get(s2).get("seller_legal_name")).startsWith("Epicerie ");
        assertThat(rows.get(s3)).containsEntry("status", "CANCELLED").containsEntry("payment_state", null)
                .containsEntry("cancellation_reason", "Annulée dans Bref");
        assertThat(rows.get(s4).get("deleted_at")).isNotNull();
        assertThat(rows.get(s4)).containsEntry("legacy_public_token", null);
        assertThat(rows.get(s5)).containsEntry("total_ttc", 1501L).containsEntry("payment_state", "UNPAID");
        assertThat(rows.get(s6)).containsEntry("status", "ISSUED").containsEntry("payment_state", "PAID");
        assertThat(rows.get(s7)).containsEntry("status", "PAID").containsEntry("payment_state", "PAID");
        assertThat((String) rows.get(s8).get("number")).endsWith("-2");
        assertThat(((java.sql.Timestamp) rows.get(s1).get("created_at")).toInstant()).isEqualTo(T0.plusSeconds(1));

        // lines: linked to the product of their key in their shop
        Map<UUID, UUID> productOfInvoice = new HashMap<>();
        jdbc.queryForList("SELECT invoice_id, product_id FROM \"" + schema + "\".invoice_items WHERE position = 1")
                .forEach(r -> productOfInvoice.put((UUID) r.get("invoice_id"), (UUID) r.get("product_id")));
        assertThat(productOfInvoice.get(s1)).isNotNull();
        assertThat(productOfInvoice.get(quote)).isEqualTo(productOfInvoice.get(s1));
        assertThat(productOfInvoice.get(s2)).isNotNull().isNotEqualTo(productOfInvoice.get(s1));
        assertThat(productOfInvoice.get(s5)).isNull();
        // several lines keep Bref's saved order (positions 1..n)
        assertThat(jdbc.queryForList("SELECT description FROM \"" + schema + "\".invoice_items WHERE invoice_id = ? "
                + "ORDER BY position", String.class, s2)).containsExactly("Savon", "Bougie", "Allumettes");

        // the anomaly's Bref values survive in its record
        assertThat(jdbc.queryForObject("SELECT note FROM public.migration_records WHERE source_table = 'invoice' "
                + "AND source_id = ? AND target_table = 'invoices'", String.class, s5))
                .contains("total Bref 1500.5", "recalcul 1000");

        // read back by the invoicing module itself
        TenantContext.setCurrentTenant(schema);
        Invoice read = invoices.findById(s2).orElseThrow();
        TenantContext.clear();
        assertThat(read.number()).startsWith("BRF-");
        assertThat(read.seller().legalName()).startsWith("Epicerie ");
        assertThat(read.lines()).singleElement().satisfies(l -> assertThat(l.productId()).isNotNull());
        assertThat(read.totals().totalTtc()).isEqualTo(11925);

        // settings from the oldest shop, 90-day links, the old link registered
        Map<String, Object> settings = jdbc.queryForMap("SELECT legal_name, vat_registered, prices_include_vat, niu "
                + "FROM \"" + schema + "\".invoicing_settings");
        assertThat((String) settings.get("legal_name")).startsWith("Electro ");
        assertThat(settings).containsEntry("vat_registered", false).containsEntry("prices_include_vat", false)
                .containsEntry("niu", "M0123");
        assertThat(jdbc.queryForObject("SELECT public_link_days FROM \"" + schema
                + "\".invoicing_document_preferences", Integer.class)).isEqualTo(90);
        assertThat(jdbc.queryForMap("SELECT tenant_schema, invoice_id FROM public.invoice_legacy_links WHERE token = ?",
                tokenS1)).containsEntry("tenant_schema", schema).containsEntry("invoice_id", s1);

        // sums per store and civil month = the source (±1 F per document), collected = the source
        Map<String, long[]> keevo = new HashMap<>();
        jdbc.queryForList("SELECT store_id::text AS s, to_char(issue_date, 'YYYY-MM') AS m, sum(total_ttc) AS t, "
                + "count(*) AS n FROM " + inv + " WHERE kind = 'INVOICE' AND status <> 'CANCELLED' "
                + "AND deleted_at IS NULL GROUP BY 1, 2").forEach(r -> keevo.put(r.get("s") + "|" + r.get("m"),
                new long[]{((Number) r.get("t")).longValue(), ((Number) r.get("n")).longValue()}));
        List<Map<String, Object>> source = bref.queryForList("SELECT lower(btrim(\"shopId\")) AS s, substr(date, 1, 7) "
                + "AS m, sum(total) AS t, count(*) AS n FROM invoice WHERE type = 'sale' AND status <> 'cancelled' "
                + "AND \"deletedAt\" IS NULL AND lower(btrim(\"shopId\")) IN (?, ?) GROUP BY 1, 2", a,
                shopB.toString());
        assertThat(keevo).hasSameSizeAs(source);
        for (Map<String, Object> r : source) {
            long[] k = keevo.get(r.get("s") + "|" + r.get("m"));
            assertThat(k).as("%s", r).isNotNull();
            assertThat(k[1]).isEqualTo(((Number) r.get("n")).longValue());
            assertThat(BigDecimal.valueOf(k[0]).subtract((BigDecimal) r.get("t")).abs())
                    .isLessThanOrEqualTo(BigDecimal.valueOf(k[1]));
        }
        BigDecimal collected = bref.queryForObject("SELECT (SELECT sum(\"initialPayment\") FROM invoice WHERE "
                + "lower(btrim(\"shopId\")) IN (?, ?)) + (SELECT coalesce(sum(p.amount), 0) FROM invoice_payment p "
                + "JOIN invoice i ON i.id = p.\"invoiceId\" WHERE p.\"isPaid\" AND lower(btrim(i.\"shopId\")) IN (?, ?))",
                BigDecimal.class, a, shopB.toString(), a, shopB.toString());
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM \"" + schema + "\".invoice_payments "
                + "WHERE paid_at IS NOT NULL", BigDecimal.class)).isEqualByComparingTo(collected);

        // expenses: exact amounts, archived, « Autre », method dropped
        Map<UUID, Map<String, Object>> exp = new HashMap<>();
        jdbc.queryForList("SELECT * FROM \"" + schema + "\".expenses").forEach(r -> exp.put((UUID) r.get("id"), r));
        assertThat(exp).hasSize(4);
        assertThat((BigDecimal) exp.get(e1).get("amount")).isEqualByComparingTo("15000.50");
        assertThat(exp.get(e1)).containsEntry("store_id", shopA).containsEntry("category", "rent")
                .containsEntry("payment_method", "cash").containsEntry("label", "Dépense test");
        assertThat(exp.get(e2)).containsEntry("store_id", shopB);
        assertThat(exp.get(e3).get("archived_at")).isNotNull();
        assertThat(exp.get(e4)).containsEntry("category", "other").containsEntry("payment_method", null);

        // ── re-run: nothing written
        int records = count("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ?", owner);
        JsonNode again = data(importOwner(owner, "DATABASE", token));
        assertThat(again.path("status").asText()).isEqualTo("COMPLETED");
        for (String key : List.of("quotesCreated", "invoicesCreated", "invoiceLinesCreated", "paymentsCreated",
                "expensesCreated")) {
            assertThat(again.path("counts").path(key).asInt()).as(key).isZero();
        }
        assertThat(again.path("counts").path("alreadyImported").asInt()).isEqualTo(records);
        assertThat(again.path("notes").toString()).doesNotContain("DOCUMENT_", "EXPENSE_");

        // ── partial re-run: one sale and one expense added since
        UUID s9 = invoice(UUID.randomUUID(), owner, a, "sale", "paid", "900", "0", "0", "0", "900", "900",
                "2026-05-01", null, null, T0.plusSeconds(20), null);
        item(s9, "Nouveau", "REF-N", "1", "900", "900");
        expense(owner, shopA, "50", "telecom", "2026-05-02", "card", null);
        payment(s2, "6925", "2026-03-10", true, "2026-05-03"); // the rest of an imported invoice, collected since
        JsonNode partial = data(importOwner(owner, "DATABASE", token));
        assertThat(partial.path("status").asText()).as(partial.toString()).isEqualTo("COMPLETED");
        assertThat(partial.path("counts").path("invoicesCreated").asInt()).isEqualTo(1);
        assertThat(partial.path("counts").path("invoiceLinesCreated").asInt()).isEqualTo(1);
        assertThat(partial.path("counts").path("paymentsCreated").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payment_state FROM " + inv + " WHERE id = ?", String.class, s2))
                .isEqualTo("PAID");
        assertThat(partial.path("counts").path("expensesCreated").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payment_state FROM " + inv + " WHERE id = ?", String.class, s9))
                .isEqualTo("PAID");

        // ── the export of the same owner gives the same documents and expenses, and imports nothing new
        writeExport(owner, a, shopB.toString());
        BrefSnapshot fromDb = source(SourceKind.DATABASE).load(owner);
        BrefSnapshot fromExport = source(SourceKind.EXPORT).load(owner);
        assertThat(fromExport.invoices()).hasSize(10).containsExactlyInAnyOrderElementsOf(fromDb.invoices());
        assertThat(fromExport.expenses()).hasSize(5).containsExactlyInAnyOrderElementsOf(fromDb.expenses());
        JsonNode viaExport = data(importOwner(owner, "EXPORT", token));
        assertThat(viaExport.path("status").asText()).as(viaExport.toString()).isEqualTo("COMPLETED");
        assertThat(viaExport.path("counts").path("invoicesCreated").asInt()).isZero();
        assertThat(viaExport.path("counts").path("expensesCreated").asInt()).isZero();
    }

    @Test
    @DisplayName("a document Keevo cannot hold (discount above the subtotal) blocks the owner before any write")
    void invalidDocumentBlocks() throws Exception {
        UUID owner = owner();
        UUID shop = shop(owner, "Boutique " + tag(), "other", T0);
        invoice(UUID.randomUUID(), owner, shop.toString(), "sale", "pending", "100", "0", "200", "0", "0", "0",
                "2026-03-01", null, null, T0, null);
        JsonNode batch = data(importOwner(owner, "DATABASE", adminToken()));
        assertThat(batch.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(batch.path("cases").toString()).contains("DOCUMENT_INVALID", "remise supérieure au sous-total");
        assertThat(count("SELECT count(*) FROM public.users WHERE id = ?", owner)).isZero();
    }

    @Autowired com.keevo.invoicing.invoice.domain.port.out.LegacyInvoiceLinkRegistry legacyLinks;

    @Test
    @DisplayName("old Bref links in one statement: inserted with Bref's expiry, a replay refreshes it, a token of another invoice is never moved")
    void legacyLinksBatch() {
        UUID token = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        Instant first = Instant.parse("2026-06-01T10:00:00Z");
        Instant later = Instant.parse("2026-07-01T10:00:00Z");
        String schemaA = "kv_t244aa";
        legacyLinks.registerAll(schemaA, List.of(new com.keevo.invoicing.invoice.domain.port.out
                .LegacyInvoiceLinkRegistry.Link(token, invoice, first)));
        assertThat(legacyLinks.find(token)).hasValueSatisfying(t -> {
            assertThat(t.invoiceId()).isEqualTo(invoice);
            assertThat(t.expiresAt()).isEqualTo(first);
        });
        legacyLinks.registerAll(schemaA, List.of(new com.keevo.invoicing.invoice.domain.port.out
                .LegacyInvoiceLinkRegistry.Link(token, invoice, later)));
        assertThat(legacyLinks.find(token)).hasValueSatisfying(t -> assertThat(t.expiresAt()).isEqualTo(later));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> legacyLinks.registerAll(schemaA, List.of(
                new com.keevo.invoicing.invoice.domain.port.out.LegacyInvoiceLinkRegistry.Link(token,
                        UUID.randomUUID(), first)))).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> legacyLinks.registerAll("kv_t244bb", List.of(
                new com.keevo.invoicing.invoice.domain.port.out.LegacyInvoiceLinkRegistry.Link(token, invoice,
                        first)))).isInstanceOf(IllegalStateException.class);
        assertThat(legacyLinks.find(token)).hasValueSatisfying(t -> {
            assertThat(t.tenantSchema()).isEqualTo(schemaA);
            assertThat(t.invoiceId()).isEqualTo(invoice);
            assertThat(t.expiresAt()).isEqualTo(later);
        });
    }

    // ── export writer (the contract's shape, built from the synthetic Bref rows) ──────────────────────────

    private BrefSourcePort source(SourceKind kind) {
        return sources.stream().filter(s -> s.kind() == kind).findFirst().orElseThrow();
    }

    private void writeExport(UUID owner, String shopA, String shopB) throws Exception {
        String json = bref.queryForObject("""
                SELECT json_build_object(
                  'formatVersion', 1, 'source', 'api-bref', 'exportedAt', '2026-11-10T08:00:00.000Z',
                  'owner', (SELECT to_jsonb(u) FROM "user" u WHERE u.id = ?),
                  'shops', COALESCE((SELECT jsonb_agg(to_jsonb(s) ORDER BY s."createdAt") FROM shop s
                                     WHERE s."userId" = ?), '[]'::jsonb),
                  'members', '[]'::jsonb,
                  'invoices', COALESCE((SELECT jsonb_agg(to_jsonb(i) || jsonb_build_object(
                       'subtotal', i.subtotal::text, 'tax', i.tax::text, 'discount', i.discount::text,
                       'deliveryFee', i."deliveryFee"::text, 'total', i.total::text,
                       'initialPayment', i."initialPayment"::text,
                       'items', COALESCE((SELECT jsonb_agg(to_jsonb(it) - 'invoiceId' || jsonb_build_object(
                                  'quantity', it.quantity::text, 'unitPrice', it."unitPrice"::text,
                                  'total', it.total::text)) FROM invoice_item it WHERE it."invoiceId" = i.id),
                                '[]'::jsonb),
                       'payments', COALESCE((SELECT jsonb_agg(to_jsonb(p) || jsonb_build_object(
                                  'amount', p.amount::text) ORDER BY p."createdAt", p.id) FROM invoice_payment p
                                  WHERE p."invoiceId" = i.id),
                                '[]'::jsonb))
                     ORDER BY i."createdAt") FROM invoice i WHERE lower(btrim(i."shopId")) IN (?, ?)), '[]'::jsonb),
                  'expenses', COALESCE((SELECT jsonb_agg(to_jsonb(e) || jsonb_build_object('amount', e.amount::text)
                     ORDER BY e."createdAt") FROM expense e WHERE lower(btrim(e."shopId")) IN (?, ?)), '[]'::jsonb)
                )::text
                """, String.class, owner, owner, shopA, shopB, shopA, shopB);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Path file = EXPORT_DIR.resolve("owner-" + owner + ".json");
        Files.write(file, bytes);
        Files.writeString(EXPORT_DIR.resolve("owner-" + owner + ".json.sha256"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "  "
                        + file.getFileName() + "\n");
    }
}
