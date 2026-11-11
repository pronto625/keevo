package com.keevo.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.migration.domain.port.in.BrefReconciliationUseCase;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 24.8 — the reconciliation end to end through the super-admin API and the production beans, from a synthetic
 * Bref database (structure of {@code migration/bref-source-schema.sql} + monetisation, random ids and phones, no real
 * data): two shops, a product present in both (merged by key), a live and a deleted member, a quote, paid / partial /
 * cancelled / deleted / anomalous sales (cents, total ≠ recalcul, payment of 0 F, shared prefix, old links), expenses,
 * a plan in force with a scheduled period turned into credit. Checks: automatic RECONCILED after the import, the report
 * (every §10.3 control, no personal data), the gate, a tampered Keevo tenant → RECONCILIATION_FAILED, a re-run, the
 * correction, a blocked owner (409/404), unknown batch (404).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("24.8 — automatic and blocking reconciliation of a Bref owner")
class BrefReconciliationFullContextTest extends FullContextIntegrationTest {

    private static final String BREF_DB = "bref_src_it248";
    private static final Duration DAY = Duration.ofDays(1);

    static {
        SharedPostgresContainer.freshDatabase(BREF_DB);
    }

    @DynamicPropertySource
    static void brefSource(DynamicPropertyRegistry registry) {
        registry.add("keevo.migration.bref.jdbc.url", () -> SharedPostgresContainer.jdbcUrl(BREF_DB));
        registry.add("keevo.migration.bref.jdbc.username", SharedPostgresContainer::username);
        registry.add("keevo.migration.bref.jdbc.password", SharedPostgresContainer::password);
    }

    private static JdbcTemplate bref;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired BrefReconciliationUseCase reconciliation;

    private final UUID adminId = UUID.randomUUID();
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final Instant T0 = Instant.parse("2026-03-01T08:00:00.123Z");
    private static final String HASH = "$2b$10$abcdefghijklmnopqrstuuJ0pOaKGXUqLqRZ1xWd9ycfhzbMSqHmu";

    @BeforeAll
    static void brefSchema() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource(SharedPostgresContainer.jdbcUrl(BREF_DB),
                SharedPostgresContainer.username(), SharedPostgresContainer.password());
        bref = new JdbcTemplate(ds);
        bref.execute(new ClassPathResource("migration/bref-source-schema.sql").getContentAsString(StandardCharsets.UTF_8));
        bref.execute(new ClassPathResource("migration/bref-source-monetisation-schema.sql")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── fixtures (synthetic Bref rows) ───────────────────────────────────────

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

    private static UUID member(UUID shop, Instant deletedAt) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO \"user\" (id, password, \"firstName\", \"lastName\", phone, role, \"shopId\", "
                        + "\"isActive\", \"isVerified\", \"createdAt\", \"updatedAt\", \"deletedAt\") VALUES "
                        + "(?, ?, 'Prénom', 'Nom', ?, 'seller', ?, true, true, ?, ?, ?)",
                id, HASH, phone(), shop.toString(), utc(T0), utc(T0), utc(deletedAt));
        return id;
    }

    private static UUID shop(UUID owner, String name, Instant createdAt) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO shop (id, \"userId\", name, address, category, phone, country, \"createdAt\", "
                        + "\"updatedAt\") VALUES (?, ?, ?, 'Adresse', 'food', '699000111', 'CM', ?, ?)",
                id, owner, name, utc(createdAt), utc(createdAt));
        return id;
    }

    private static void product(String shopId, String name, String key) {
        bref.update("INSERT INTO product (id, \"shopId\", name, \"nameKey\", \"productKey\", \"unitPrice\", "
                        + "\"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, 1000, ?, ?)",
                UUID.randomUUID(), shopId, name, name, key, utc(T0), utc(T0));
    }

    private static void customer(UUID shop, String phone) {
        bref.update("INSERT INTO customer (id, \"shopId\", name, phone, \"createdAt\", \"updatedAt\") "
                + "VALUES (?, ?, 'Client Test', ?, ?, ?)", UUID.randomUUID(), shop, phone, utc(T0), utc(T0));
    }

    private static UUID invoice(UUID id, UUID owner, String shopId, String type, String status, String subtotal,
                                String total, String initial, String date, UUID token, Instant createdAt,
                                Instant deletedAt) {
        bref.update("INSERT INTO invoice (id, \"userId\", \"shopId\", \"customerName\", \"customerPhone\", subtotal, "
                        + "tax, discount, \"deliveryFee\", total, status, \"paymentMethod\", \"initialPayment\", date, "
                        + "\"publicToken\", \"publicTokenExpiresAt\", type, \"createdAt\", \"updatedAt\", "
                        + "\"deletedAt\", \"cancelledAt\") VALUES (?, ?, ?, 'Awa Cliente', NULL, ?::numeric, 0, 0, 0, "
                        + "?::numeric, ?::invoice_status_enum, 'cash', ?::numeric, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, owner.toString(), shopId, subtotal, total, status, initial, date, token,
                token == null ? null : utc(createdAt.plusSeconds(90L * 86400)), type, utc(createdAt),
                utc(createdAt.plusSeconds(60)), utc(deletedAt),
                "cancelled".equals(status) ? utc(createdAt.plusSeconds(30)) : null);
        return id;
    }

    private static void item(UUID invoice, String name, String total) {
        bref.update("INSERT INTO invoice_item (id, \"invoiceId\", reference, name, quantity, \"unitPrice\", total) "
                        + "VALUES (?, ?, 'REF-1', ?, 1, ?::numeric, ?::numeric)", UUID.randomUUID(), invoice, name, total,
                total);
    }

    private static void payment(UUID invoice, String amount, boolean paid) {
        bref.update("INSERT INTO invoice_payment (id, \"invoiceId\", amount, \"dueDate\", \"isPaid\", \"paidDate\", "
                        + "\"createdAt\", \"updatedAt\") VALUES (?, ?, ?::numeric, '2026-03-10', ?, ?, ?, ?)",
                UUID.randomUUID(), invoice, amount, paid, paid ? "2026-03-10" : null, utc(T0), utc(T0));
    }

    private static void expense(UUID owner, UUID shop, String amount, String date, Instant deletedAt) {
        bref.update("INSERT INTO expense (id, \"ownerId\", \"shopId\", \"createdById\", amount, category, description, "
                        + "\"expenseDate\", \"paymentMethod\", \"createdAt\", \"updatedAt\", \"deletedAt\") VALUES "
                        + "(?, ?, ?, ?, ?::numeric, 'rent', 'Dépense test', ?::date, 'cash', ?, ?, ?)",
                UUID.randomUUID(), owner.toString(), shop.toString(), owner.toString(), amount, date, utc(T0),
                utc(T0), utc(deletedAt));
    }

    private void subscription(UUID owner, String plan, String status, Instant start, Instant end, long paid) {
        bref.update("INSERT INTO subscription (id, \"ownerId\", plan, status, \"billingCycle\", \"startedAt\", "
                        + "\"endsAt\", \"amountPaid\", \"creditUsed\", \"paymentMethod\", \"createdAt\", \"updatedAt\") "
                        + "VALUES (?, ?, ?, ?, 'monthly', ?, ?, ?, 0, 'momo', ?, ?)",
                UUID.randomUUID(), owner, plan, status, utc(start), utc(end), paid, utc(start), utc(start));
    }

    // ── API ──────────────────────────────────────────────────────────────────

    private String adminToken() {
        return TestAccess.token(jdbc, jwtTokenProvider, adminId, "public", "SUPER_ADMIN");
    }

    private MvcResult importOwner(UUID owner, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/migration/bref/owners/" + owner + "/imports")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"DATABASE\"}")).andReturn();
    }

    private MvcResult call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                           String token) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token)).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode check(JsonNode report, String code) {
        for (JsonNode c : report.path("checks")) {
            if (code.equals(c.path("code").asText())) {
                return c;
            }
        }
        throw new AssertionError("check " + code + " missing in " + report);
    }

    private static List<String> gapLabels(JsonNode report) {
        List<String> gaps = new ArrayList<>();
        for (JsonNode c : report.path("checks")) {
            for (JsonNode l : c.path("lines")) {
                if ("GAP".equals(l.path("status").asText())) {
                    gaps.add(c.path("code").asText() + " / " + l.path("label").asText());
                }
            }
        }
        return gaps;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("import → RECONCILED automatically; tampered Keevo → RECONCILIATION_FAILED and blocking; corrected → clear")
    void reconcileTamperCorrect() throws Exception {
        UUID owner = owner();
        String shopName = "Epicerie " + tag();
        UUID shopA = shop(owner, shopName, T0);
        UUID shopB = shop(owner, "Bazar " + tag(), T0.plusSeconds(100));
        String a = shopA.toString();
        String bUpper = shopB.toString().toUpperCase(java.util.Locale.ROOT);
        product(a, "Riz 5 kg", "nm:riz 5 kg");
        product(bUpper, "Riz 5 kg", "nm:riz 5 kg"); // same key in both shops: one Keevo product
        product(a, "Savon", "nm:savon");
        String clientPhone = phone();
        customer(shopA, clientPhone);
        member(shopA, null);
        member(shopB, T0.plusSeconds(10)); // deleted: not imported, explained

        UUID quote = invoice(UUID.randomUUID(), owner, a, "quote", "pending", "6000", "6000", "0", "2026-03-01",
                UUID.randomUUID(), T0, null);
        item(quote, "Riz 5 kg", "6000");
        UUID s1 = invoice(UUID.randomUUID(), owner, a, "sale", "paid", "25000", "25000", "25000", "2026-03-02",
                UUID.randomUUID(), T0.plusSeconds(1), null);
        item(s1, "Riz 5 kg", "25000");
        UUID s2 = invoice(UUID.randomUUID(), owner, bUpper, "sale", "partial", "10000", "10000", "2000",
                "2026-03-03", null, T0.plusSeconds(2), null);
        item(s2, "Savon", "10000");
        payment(s2, "3000", true);
        payment(s2, "5000", false);
        payment(s2, "0.2", true); // rounds to 0 F: not imported, explained
        UUID s3 = invoice(UUID.randomUUID(), owner, a, "sale", "cancelled", "4000", "4000", "500", "2026-03-04",
                UUID.randomUUID(), T0.plusSeconds(3), null);
        item(s3, "Annulé", "4000");
        UUID s4 = invoice(UUID.randomUUID(), owner, bUpper, "sale", "paid", "700", "700", "700", "2026-03-05",
                UUID.randomUUID(), T0.plusSeconds(4), T0.plusSeconds(50));
        item(s4, "Supprimé", "700");
        UUID s5 = invoice(UUID.randomUUID(), owner, a, "sale", "pending", "1000", "1500.50", "0", "2026-04-02",
                UUID.randomUUID(), T0.plusSeconds(5), null); // cents + total ≠ recalcul: kept, noted
        item(s5, "Anomalie", "1000");
        UUID s6 = invoice(UUID.fromString(s1.toString().substring(0, 8) + "-1111-4111-8111-111111111111"), owner, a,
                "sale", "paid", "300", "300", "300", "2026-04-05", null, T0.plusSeconds(8), null);
        item(s6, "Même préfixe", "300");
        expense(owner, shopA, "15000.50", "2026-03-05", null);
        expense(owner, shopB, "20000", "2026-04-01", null);
        expense(owner, shopA, "100", "2026-04-02", T0.plusSeconds(9));
        Instant start = now.minus(DAY.multipliedBy(5));
        Instant end = now.plus(DAY.multipliedBy(25));
        subscription(owner, "pro", "active", start, end, 3000);
        subscription(owner, "team", "scheduled", end, end.plus(DAY.multipliedBy(30)), 5500);
        bref.update("INSERT INTO account_credit (id, \"ownerId\", amount, \"updatedAt\") VALUES (?, ?, 1750, ?)",
                UUID.randomUUID(), owner, utc(now));
        String token = adminToken();

        // ── import: reconciled automatically
        MvcResult imported = importOwner(owner, token);
        assertThat(imported.getResponse().getStatus()).as(imported.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode batch = body(imported).path("data");
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        UUID batchId = UUID.fromString(batch.path("id").asText());
        UUID tenantId = UUID.fromString(batch.path("tenantId").asText());
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class,
                tenantId);

        MvcResult got = call(get("/api/v1/admin/migration/batches/" + batchId + "/reconciliation"), token);
        assertThat(got.getResponse().getStatus()).isEqualTo(200);
        String raw = got.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode report = body(got).path("data");
        assertThat(gapLabels(report)).as(raw).isEmpty();
        assertThat(report.path("status").asText()).isEqualTo("RECONCILED");
        assertThat(report.path("statusLabel").asText()).isEqualTo("Réconcilié");
        assertThat(report.path("checks")).hasSize(9);
        assertThat(report.path("explained").asInt()).isPositive();
        // aggregates only: no shop name, no client name, no phone, no Bref id in the labels
        assertThat(raw).doesNotContain(shopName, "Awa Cliente", clientPhone, s1.toString(), owner.toString() + " ");
        JsonNode counts = check(report, "COUNTS");
        Map<String, JsonNode> byLabel = new HashMap<>();
        counts.path("lines").forEach(l -> byLabel.put(l.path("label").asText(), l));
        assertThat(byLabel.get("Produits").path("status").asText()).isEqualTo("EXPLAINED");
        assertThat(byLabel.get("Produits").path("expected").asText()).isEqualTo("2");
        assertThat(byLabel.get("Membres de l'équipe (comptes et invitations)").path("expected").asText())
                .isEqualTo("1");
        assertThat(byLabel.get("Encaissements et échéances (acomptes compris)").path("status").asText())
                .isEqualTo("EXPLAINED");
        assertThat(check(report, "SALES_BY_STORE_MONTH").path("lines").toString()).contains("Boutique 1 — mars 2026",
                "Boutique 2 — mars 2026", "Boutique 1 — avril 2026");
        assertThat(check(report, "SAMPLE").path("lines")).hasSize(7);
        assertThat(check(report, "SUBSCRIPTION").path("status").asText()).isEqualTo("OK");
        assertThat(check(report, "SUBSCRIPTION").path("lines").get(2).path("expected").asText())
                .isEqualTo("7250 F"); // 1 750 of credit + the scheduled TEAM period (5 500)
        assertThat(check(report, "PUBLIC_LINKS").path("lines")).hasSize(4); // the deleted one has no link
        assertThat(check(report, "NOTED_ANOMALIES").path("status").asText()).isEqualTo("EXPLAINED");

        UUID never = UUID.randomUUID();
        assertThat(reconciliation.blockingOwners(List.of(owner, never)))
                .containsOnlyKeys(never).containsEntry(never, "Compte Bref non importé");

        // ── tamper the imported tenant: a sale +10 F, an expense gone, a paid instalment gone, the credit +1
        String q = "\"" + schema + "\".";
        jdbc.update("UPDATE " + q + "invoices SET total_ttc = total_ttc + 10 WHERE id = ?", s2);
        jdbc.update("DELETE FROM " + q + "expenses WHERE amount = 20000");
        jdbc.update("DELETE FROM " + q + "invoice_payments WHERE invoice_id = ? AND amount = 3000", s2);
        jdbc.update("UPDATE public.tenant_credits SET balance_xaf = balance_xaf + 1 WHERE tenant_id = ?", tenantId);

        MvcResult again = call(post("/api/v1/admin/migration/batches/" + batchId + "/reconciliation"), token);
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        JsonNode failed = body(again).path("data");
        assertThat(failed.path("status").asText()).isEqualTo("RECONCILIATION_FAILED");
        assertThat(gapLabels(failed)).contains("SALES_BY_STORE_MONTH / Boutique 2 — mars 2026",
                "EXPENSES_BY_MONTH / avril 2026", "COUNTS / Dépenses (archivées comprises)",
                "COUNTS / Encaissements et échéances (acomptes compris)", "COLLECTED / Total encaissé",
                "OUTSTANDING / Restant dû total", "SUBSCRIPTION / Crédit du compte");
        assertThat(failed.path("gaps").asInt()).isGreaterThanOrEqualTo(7);
        assertThat(reconciliation.blockingOwners(List.of(owner)))
                .containsEntry(owner, "Écart de réconciliation à corriger");
        JsonNode latest = body(call(get("/api/v1/admin/migration/bref/owners/" + owner + "/reconciliation"), token))
                .path("data");
        assertThat(latest.path("id").asText()).isEqualTo(failed.path("id").asText());

        // ── a re-run of the import writes nothing: the new batch is reconciled and still failed
        JsonNode rerun = body(importOwner(owner, token)).path("data");
        assertThat(rerun.path("status").asText()).isEqualTo("COMPLETED");
        JsonNode rerunReport = body(call(get("/api/v1/admin/migration/batches/" + rerun.path("id").asText()
                + "/reconciliation"), token)).path("data");
        assertThat(rerunReport.path("status").asText()).isEqualTo("RECONCILIATION_FAILED");

        // ── correction in Keevo, then a new reconciliation: clear
        jdbc.update("UPDATE " + q + "invoices SET total_ttc = total_ttc - 10 WHERE id = ?", s2);
        jdbc.update("UPDATE public.tenant_credits SET balance_xaf = balance_xaf - 1 WHERE tenant_id = ?", tenantId);
        // the deleted rows come back by a third import (ON CONFLICT DO NOTHING completes what is missing)
        jdbc.update("DELETE FROM public.migration_records WHERE bref_owner_id = ? AND target_table IN "
                + "('expenses', 'invoice_payments') AND target_id NOT IN (SELECT id FROM " + q + "expenses UNION "
                + "SELECT id FROM " + q + "invoice_payments)", owner);
        JsonNode third = body(importOwner(owner, token)).path("data");
        assertThat(third.path("status").asText()).as(third.toString()).isEqualTo("COMPLETED");
        JsonNode cleared = body(call(get("/api/v1/admin/migration/batches/" + third.path("id").asText()
                + "/reconciliation"), token)).path("data");
        assertThat(gapLabels(cleared)).isEmpty();
        assertThat(cleared.path("status").asText()).isEqualTo("RECONCILED");
        assertThat(reconciliation.blockingOwners(List.of(owner))).isEmpty();
    }

    @Test
    @DisplayName("blocked import: no reconciliation (404), cannot be reconciled (409), blocks the activation; unknown batch 404")
    void blockedAndUnknown() throws Exception {
        UUID owner = owner();
        UUID shop = shop(owner, "Boutique " + tag(), T0);
        UUID bad = invoice(UUID.randomUUID(), owner, shop.toString(), "sale", "pending", "-5", "-5", "0",
                "2026-03-01", null, T0, null); // negative amount: DOCUMENT_INVALID, nothing written
        item(bad, "Négatif", "1");
        String token = adminToken();

        JsonNode batch = body(importOwner(owner, token)).path("data");
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("BLOCKED");
        String id = batch.path("id").asText();

        MvcResult none = call(get("/api/v1/admin/migration/batches/" + id + "/reconciliation"), token);
        assertThat(none.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(none).toString()).contains("MIGRATION_RECONCILIATION_NOT_FOUND");
        MvcResult refused = call(post("/api/v1/admin/migration/batches/" + id + "/reconciliation"), token);
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(refused).toString()).contains("Seul un import terminé peut être réconcilié");
        assertThat(reconciliation.blockingOwners(List.of(owner)))
                .containsEntry(owner, "Dernier import non terminé (bloqué, en échec ou en cours)");

        MvcResult unknown = call(get("/api/v1/admin/migration/batches/" + UUID.randomUUID() + "/reconciliation"),
                token);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(unknown).toString()).contains("MIGRATION_BATCH_NOT_FOUND");
        MvcResult noOwner = call(get("/api/v1/admin/migration/bref/owners/" + UUID.randomUUID() + "/reconciliation"),
                token);
        assertThat(noOwner.getResponse().getStatus()).isEqualTo(404);
    }
}
