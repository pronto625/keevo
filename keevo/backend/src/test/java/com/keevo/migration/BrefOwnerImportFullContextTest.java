package com.keevo.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 24.2 — the Bref identity import end to end, through the super-admin API and the production beans: a synthetic
 * Bref database (the api-bref structure of {@code migration/bref-source-schema.sql}, random ids and phones, no real
 * data) on the test container, read by the JDBC source; then the JSON export of the same owner. Checks the tenant
 * (suspended, DOCUMENT, no « Ma Boutique », modules), stores, members (roles, bcrypt copied), records, idempotence
 * (re-run, partial re-run), a blocked collision with nothing written, the export source giving the same snapshot, a
 * logo embedded in an export, and the 403 for an owner.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("24.2 — Bref owner import (identity, company, shops, team)")
class BrefOwnerImportFullContextTest extends FullContextIntegrationTest {

    private static final String BREF_DB = "bref_src_it242";
    private static final Path EXPORT_DIR;
    /** A 1×1 PNG. */
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
    private static final String HASH_ADMIN = "$2b$10$abcdefghijklmnopqrstuuAbCdEfGhIjKlMnOpQrStUvWxYz01234";
    private static final String HASH_SELLER = "$2b$10$zyxwvutsrqponmlkjihgfeZyXwVuTsRqPoNmLkJiHgFeDcBa98765";

    static {
        SharedPostgresContainer.freshDatabase(BREF_DB);
        try {
            EXPORT_DIR = Files.createTempDirectory("bref-export-it");
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
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired List<BrefSourcePort> sources;
    @Autowired com.keevo.migration.domain.port.out.MigrationRecordStore recordStore;

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

    private static String phone() {
        return "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static String tag() {
        return Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36);
    }

    private static final Instant T0 = Instant.parse("2026-03-01T08:00:00.123Z");

    /** Bref stores UTC wall-clock times in {@code timestamp without time zone} columns (server in UTC). */
    private static java.time.LocalDateTime utc(Instant at) {
        return at == null ? null : java.time.LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC);
    }

    private static UUID user(String role, String phone, String email, String username, String hash, String shopId,
                             boolean active, boolean mustChange, Instant deletedAt) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO \"user\" (id, email, password, \"firstName\", \"lastName\", phone, role, \"shopId\", "
                        + "\"isActive\", username, \"mustChangePassword\", \"isVerified\", \"emailVerified\", "
                        + "\"createdAt\", \"updatedAt\", \"deletedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?)",
                id, email, hash, "Prénom", role.equals("viewer") ? null : "Nom", phone, role, shopId, active, username,
                mustChange, phone != null, email != null, utc(T0), utc(T0.plusSeconds(60)), utc(deletedAt));
        return id;
    }

    private static UUID shop(UUID owner, String name, String category, String photoUrl, Instant createdAt,
                             Instant deletedAt) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO shop (id, \"userId\", name, address, category, phone, \"photoUrl\", niu, country, "
                        + "\"createdAt\", \"updatedAt\", \"deletedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CM', ?, ?, ?)",
                id, owner, name, "Adresse " + name, category, "699000111", photoUrl, "M0123", utc(createdAt),
                utc(createdAt.plusSeconds(30)), utc(deletedAt));
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
    @DisplayName("import, re-run (nothing written), partial re-run (new shop and member only), export gives the same snapshot")
    void importRerunPartialAndExport() throws Exception {
        String ownerPhone = phone();
        UUID owner = user("owner", ownerPhone, "o" + tag() + "@example.test", null, null, null, true, false, null);
        UUID shopA = shop(owner, "Boutique " + tag(), "electronics", "https://res.cloudinary.com/demo/image/upload/x.png",
                T0, null);
        UUID shopB = shop(owner, "Ancienne " + tag(), "food", null, T0.minusSeconds(3600), T0.plusSeconds(10));
        UUID admin = user("admin", null, null, "g" + tag(), HASH_ADMIN, shopA.toString(), true, false, null);
        UUID seller = user("seller", null, null, "v" + tag(), HASH_SELLER, shopA.toString(), true, false, null);
        UUID sleeper = user("seller", null, null, "s" + tag(), HASH_SELLER, shopB.toString(), false, true, null);
        user("viewer", null, null, "d" + tag(), HASH_SELLER, shopA.toString(), true, false, T0.plusSeconds(5));
        String token = adminToken();

        // ── first import
        MvcResult first = importOwner(owner, "DATABASE", token);
        assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode batch = data(first);
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        assertThat(batch.path("statusLabel").asText()).isEqualTo("Terminé");
        assertThat(batch.path("sourceLabel").asText()).isEqualTo("Base Bref (lecture seule)");
        JsonNode counts = batch.path("counts");
        assertThat(counts.path("tenantsCreated").asInt()).isEqualTo(1);
        assertThat(counts.path("usersCreated").asInt()).isEqualTo(4);
        assertThat(counts.path("membershipsCreated").asInt()).isEqualTo(4);
        assertThat(counts.path("storesCreated").asInt()).isEqualTo(2);
        assertThat(counts.path("employeesCreated").asInt()).isEqualTo(3);
        assertThat(counts.path("membersSkippedDeleted").asInt()).isEqualTo(1);
        assertThat(counts.path("logosImported").asInt()).isZero();
        assertThat(batch.path("notes").toString()).contains("SHOP_DELETED", "MEMBER_DELETED", "MEMBER_INACTIVE",
                "LOGO_NOT_FETCHED");

        UUID tenantId = UUID.fromString(batch.path("tenantId").asText());
        Map<String, Object> tenant = jdbc.queryForMap("SELECT schema_name, status, name, suspended_reason, "
                + "suspended_at FROM public.tenants WHERE id = ?", tenantId);
        String schema = (String) tenant.get("schema_name");
        assertThat(tenant.get("status")).isEqualTo("SUSPENDED");
        assertThat((String) tenant.get("name")).startsWith("Boutique ");
        assertThat(tenant.get("suspended_reason")).isEqualTo("Migration Bref en cours — activation en attente");
        assertThat(tenant.get("suspended_at")).isNotNull();
        assertThat(count("SELECT count(*) FROM public.tenant_plan_events WHERE tenant_id = ? AND cause = "
                + "'MIGRATION_BREF'", tenantId)).isEqualTo(1);

        // stores: the Bref ids, no « Ma Boutique », the deleted shop inactive, dates kept, phone E.164
        List<Map<String, Object>> stores = jdbc.queryForList("SELECT id, name, is_active, category, phone, created_at "
                + "FROM \"" + schema + "\".stores ORDER BY created_at");
        assertThat(stores).extracting(s -> s.get("id")).containsExactly(shopB, shopA);
        assertThat(stores).extracting(s -> s.get("is_active")).containsExactly(false, true);
        assertThat(stores.get(1).get("category")).isEqualTo("electronics");
        assertThat(stores.get(1).get("phone")).isEqualTo("+237699000111");
        assertThat(((Timestamp) stores.get(1).get("created_at")).toInstant()).isEqualTo(T0);

        // profile: DOCUMENT, reports off, sector of the oldest live shop, modules
        Map<String, Object> prefs = jdbc.queryForMap("SELECT default_sale_flow, sector_type, eod_report_enabled, "
                + "stock_alert_enabled FROM \"" + schema + "\".tenant_preferences");
        assertThat(prefs.get("default_sale_flow")).isEqualTo("DOCUMENT");
        assertThat(prefs.get("sector_type")).isEqualTo("ELECTRONICS");
        assertThat(prefs.get("eod_report_enabled")).isEqualTo(false);
        assertThat(prefs.get("stock_alert_enabled")).isEqualTo(false);
        List<String> disabled = jdbc.queryForList("SELECT module_id FROM public.tenant_module_activation "
                + "WHERE tenant_id = ? AND NOT activated ORDER BY module_id", String.class, tenantId);
        assertThat(disabled).contains("pos", "inventory", "counting", "multi-store", "advanced-reports", "exports",
                "mcp-access").doesNotContain("invoicing", "catalog", "crm", "expenses", "finance", "reporting",
                "client-portal", "whatsapp-cloud", "ai-assistant");

        // accounts and team: Bref ids, bcrypt copied, roles mapped, employees on their store
        assertThat(jdbc.queryForMap("SELECT role, phone_number, password_hash, phone_verified_at IS NOT NULL AS pv "
                + "FROM public.users WHERE id = ?", owner)).containsEntry("role", "OWNER")
                .containsEntry("phone_number", ownerPhone).containsEntry("pv", true)
                .containsEntry("password_hash", null);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM public.users WHERE id = ?", String.class, admin))
                .isEqualTo(HASH_ADMIN);
        assertThat(jdbc.queryForList("SELECT user_id::text || ':' || role || ':' || is_active FROM "
                + "public.user_tenant_memberships WHERE tenant_id = ?", String.class, tenantId))
                .containsExactlyInAnyOrder(owner + ":OWNER:true", admin + ":MANAGER:true", seller + ":EMPLOYEE:true",
                        sleeper + ":EMPLOYEE:false");
        assertThat(jdbc.queryForList("SELECT id::text || ':' || store_id || ':' || status || ':' || "
                + "password_change_required FROM \"" + schema + "\".employees", String.class))
                .containsExactlyInAnyOrder(admin + ":" + shopA + ":ACTIVE:false", seller + ":" + shopA + ":ACTIVE:false",
                        sleeper + ":" + shopB + ":INACTIVE:true");
        assertThat(count("SELECT count(*) FROM \"" + schema + "\".user_roles WHERE user_id = ?", owner)).isEqualTo(1);
        int records = count("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ?", owner);
        // tenant, owner, owner membership, 2 stores, 3 × (user, membership, employee), « Produits importés » (24.3)
        assertThat(records).isEqualTo(1 + 1 + 1 + 2 + 3 * 3 + 1);

        // ── re-run: nothing written
        JsonNode again = data(importOwner(owner, "DATABASE", token));
        assertThat(again.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(again.path("id").asText()).isNotEqualTo(batch.path("id").asText());
        for (String key : List.of("tenantsCreated", "usersCreated", "membershipsCreated", "storesCreated",
                "employeesCreated")) {
            assertThat(again.path("counts").path(key).asInt()).as(key).isZero();
        }
        assertThat(again.path("counts").path("alreadyImported").asInt()).isEqualTo(records);
        assertThat(count("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ?", owner))
                .isEqualTo(records);
        assertThat(count("SELECT count(*) FROM \"" + schema + "\".stores")).isEqualTo(2);

        // ── a re-run never resets the owner's later choices (flow, modules)
        jdbc.update("UPDATE \"" + schema + "\".tenant_preferences SET default_sale_flow = 'POS'");
        jdbc.update("UPDATE public.tenant_module_activation SET activated = true WHERE tenant_id = ? AND module_id = "
                + "'exports'", tenantId);
        data(importOwner(owner, "DATABASE", token));
        assertThat(jdbc.queryForObject("SELECT default_sale_flow FROM \"" + schema + "\".tenant_preferences",
                String.class)).isEqualTo("POS");
        assertThat(jdbc.queryForObject("SELECT activated FROM public.tenant_module_activation WHERE tenant_id = ? "
                + "AND module_id = 'exports'", Boolean.class, tenantId)).isTrue();

        // ── partial re-run: a shop and a member added in Bref since
        UUID shopC = shop(owner, "Nouvelle " + tag(), "fashion", null, T0.plusSeconds(600), null);
        UUID late = user("viewer", null, null, "n" + tag(), HASH_SELLER, shopC.toString(), true, false, null);
        JsonNode partial = data(importOwner(owner, "DATABASE", token));
        assertThat(partial.path("counts").path("storesCreated").asInt()).isEqualTo(1);
        assertThat(partial.path("counts").path("usersCreated").asInt()).isEqualTo(1);
        assertThat(partial.path("counts").path("employeesCreated").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT role FROM public.user_tenant_memberships WHERE user_id = ? AND "
                + "tenant_id = ?", String.class, late, tenantId)).isEqualTo("VIEWER");

        // ── GET the batch
        MvcResult read = mockMvc.perform(get("/api/v1/admin/migration/batches/" + batch.path("id").asText())
                .header("Authorization", "Bearer " + token)).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(read).path("counts").path("tenantsCreated").asInt()).isEqualTo(1);

        // ── the export of the same owner gives the same snapshot (same rows, same checksums) and imports nothing new
        writeExport(owner, null, null);
        BrefSnapshot fromDb = source(SourceKind.DATABASE).load(owner);
        BrefSnapshot fromExport = source(SourceKind.EXPORT).load(owner);
        assertThat(fromExport.owner().canonical()).isEqualTo(fromDb.owner().canonical());
        assertThat(fromExport.shops()).containsExactlyInAnyOrderElementsOf(fromDb.shops());
        assertThat(fromExport.members().stream().map(m -> m.canonical()).toList())
                .containsExactlyInAnyOrderElementsOf(fromDb.members().stream().map(m -> m.canonical()).toList());
        JsonNode viaExport = data(importOwner(owner, "EXPORT", token));
        assertThat(viaExport.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(viaExport.path("counts").path("storesCreated").asInt()).isZero();
        assertThat(viaExport.path("counts").path("usersCreated").asInt()).isZero();
    }

    // ── Story 24.3 fixtures: synthetic catalogue and customers (no real data) ──────────────────────────────

    private static UUID product(String shopId, String name, String key, String reference, String price, boolean manual,
                                Instant lastSoldAt, boolean archived, String mergedInto, Instant createdAt) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO product (id, \"shopId\", name, \"nameKey\", \"productKey\", reference, "
                        + "\"unitPrice\", \"priceManual\", \"timesSold\", \"totalQuantity\", \"totalRevenue\", "
                        + "\"lastSoldAt\", \"isArchived\", \"mergedIntoId\", \"createdAt\", \"updatedAt\") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?, 3, 4.5, ?::numeric, ?, ?, ?, ?, ?)",
                id, shopId, name, name.toLowerCase(java.util.Locale.ROOT), key, reference, price, manual, price,
                utc(lastSoldAt), archived, mergedInto, utc(createdAt), utc(createdAt.plusSeconds(5)));
        return id;
    }

    private static UUID customer(UUID shop, String name, String phone, String email, String type, String nui,
                                 String birthDate, UUID consentBy, Instant createdAt) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO customer (id, \"shopId\", name, phone, email, type, nui, \"birthDate\", "
                        + "\"marketingConsentAt\", \"marketingConsentById\", \"totalInvoices\", \"totalSpent\", "
                        + "\"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 2, 12500.50, ?, ?)",
                id, shop, name, phone, email, type, nui, birthDate, consentBy == null ? null : utc(createdAt),
                consentBy, utc(createdAt), utc(createdAt.plusSeconds(7)));
        return id;
    }

    @Test
    @DisplayName("24.3 — categories, products merged by key across shops, Bref merges, name clash, clients per store; "
            + "re-run, partial re-run, export = database")
    void catalogueAndClients() throws Exception {
        UUID owner = user("owner", phone(), null, null, null, null, true, false, null);
        UUID shopA = shop(owner, "Electro " + tag(), "electronics", null, T0, null);
        UUID shopB = shop(owner, "Epicerie " + tag(), "food", null, T0.plusSeconds(100), null);
        UUID seller = user("seller", null, null, "p" + tag(), HASH_SELLER, shopA.toString(), true, false, null);
        String a = shopA.toString();
        String bUpper = shopB.toString().toUpperCase(java.util.Locale.ROOT); // Bref keeps product.shopId as text
        // same key in both shops: one product; the price of the latest sale (shop B)
        UUID rizA = product(a, "Riz parfumé", "nm:riz parfume", null, "1500.00", false, T0.plusSeconds(10), false,
                null, T0.plusSeconds(1));
        UUID rizB = product(bUpper, "riz parfume", "nm:riz parfume", null, "1600.00", false, T0.plusSeconds(20), false,
                null, T0.plusSeconds(2));
        // barcode key, typed price with cents; same name, other key in shop B → renamed
        UUID savonBc = product(a, "Savon", "bc:6111234567890", "6111234567890", "250.50", true, null, false, null,
                T0.plusSeconds(3));
        String bMixed = " " + shopB.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT)
                + shopB.toString().substring(8) + " "; // any case, padded: still the shop's
        UUID savonNm = product(bMixed, "Savon", "nm:savon", null, "300.00", false, null, false, null,
                T0.plusSeconds(4));
        // merged in Bref (shop A), and a merge whose target is gone
        UUID cola = product(a, "Coca-Cola 50cl", "nm:coca-cola 50cl", null, "500.00", false, null, false, null,
                T0.plusSeconds(5));
        UUID coca = product(a, "Coca", "nm:coca", null, "450.00", false, null, true, cola.toString(),
                T0.plusSeconds(6));
        UUID ghost = product(a, "Fantôme", "nm:fantome", null, "100.00", false, null, true,
                UUID.randomUUID().toString(), T0.plusSeconds(7));
        // the same phone in both shops: two clients; a local number; a company with a too long NIU
        String shared = phone();
        UUID c1 = customer(shopA, "Client Un", shared, "un@example.test", "person", null, "1990-05-01", owner,
                T0.plusSeconds(8));
        UUID c2 = customer(shopB, " ", shared, "pas-un-email", null, null, "2999-01-01", UUID.randomUUID(),
                T0.plusSeconds(9));
        UUID c3 = customer(shopA, "Entreprise Trois", "699000222", null, "company", "N".repeat(60), null, seller,
                T0.plusSeconds(10));
        String token = adminToken();

        JsonNode batch = data(importOwner(owner, "DATABASE", token));
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        JsonNode counts = batch.path("counts");
        assertThat(counts.path("productsCreated").asInt()).isEqualTo(6);
        assertThat(counts.path("productsMerged").asInt()).isEqualTo(1);
        assertThat(counts.path("clientsCreated").asInt()).isEqualTo(3);
        assertThat(counts.path("categoriesCreated").asInt()).isGreaterThan(1);
        assertThat(batch.path("notes").toString()).contains("PRODUCT_MERGED_BY_KEY", "PRODUCT_MERGED_IN_BREF",
                "PRODUCT_MERGE_BROKEN", "PRODUCT_RENAMED", "PRODUCT_PRICE_ROUNDED", "CLIENT_NAME_MISSING",
                "CLIENT_FIELD_DROPPED", "CLIENT_FIELD_SHORTENED", "CLIENT_CONSENT_AUTHOR_UNKNOWN");
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class,
                UUID.fromString(batch.path("tenantId").asText()));

        // categories: the ELECTRONICS template + « Produits importés » (custom), every product in it
        UUID imported = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".categories WHERE name = "
                + "'Produits importés' AND parent_id IS NULL AND is_custom", UUID.class);
        assertThat(count("SELECT count(*) FROM \"" + schema + "\".categories WHERE NOT is_custom"))
                .isEqualTo(counts.path("categoriesCreated").asInt() - 1).isPositive();
        assertThat(count("SELECT count(*) FROM \"" + schema + "\".products WHERE category_id IS DISTINCT FROM ?",
                imported)).isZero();

        // products
        Map<UUID, Map<String, Object>> products = new java.util.HashMap<>();
        jdbc.queryForList("SELECT id, name, sku, price, price_manual, barcode, product_key, archived, merged_into_id, "
                + "track_stock, stock_quantity, status, created_at FROM \"" + schema + "\".products")
                .forEach(r -> products.put((UUID) r.get("id"), r));
        assertThat(products.keySet()).containsExactlyInAnyOrder(rizA, savonBc, savonNm, cola, coca, ghost);
        assertThat(products.values()).allSatisfy(p -> {
            assertThat(p.get("track_stock")).isEqualTo(false);
            assertThat(p.get("stock_quantity")).isEqualTo(0);
            assertThat(p.get("status")).isEqualTo("ACTIVE");
            assertThat((String) p.get("sku")).matches("KEV-[A-Z0-9]{6}");
        });
        assertThat(products.get(rizA)).containsEntry("name", "Riz parfumé").containsEntry("price", 1600)
                .containsEntry("archived", false).containsEntry("product_key", "nm:riz parfume");
        assertThat(((Timestamp) products.get(rizA).get("created_at")).toInstant()).isEqualTo(T0.plusSeconds(1));
        assertThat(products.get(savonBc)).containsEntry("name", "Savon").containsEntry("price", 251)
                .containsEntry("price_manual", true).containsEntry("barcode", "6111234567890");
        assertThat(products.get(savonNm)).containsEntry("name", "Savon (2)").containsEntry("price", 300);
        assertThat(products.get(coca)).containsEntry("archived", true).containsEntry("merged_into_id", cola);
        assertThat(products.get(ghost)).containsEntry("archived", true).containsEntry("merged_into_id", null);
        // records: one per Bref product; the shop B rice points to the shop A rice
        assertThat(jdbc.queryForObject("SELECT target_id FROM public.migration_records WHERE source_table = 'product' "
                + "AND source_id = ?", UUID.class, rizB)).isEqualTo(rizA);
        assertThat(count("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ? AND source_table = "
                + "'product'", owner)).isEqualTo(7);

        // clients: one per Bref customer, in the store of its shop; sharing stays off
        Map<UUID, Map<String, Object>> clients = new java.util.HashMap<>();
        jdbc.queryForList("SELECT id, store_id, name, phone, phone_normalized, email, client_type, niu, birth_date, "
                + "marketing_consent_at, marketing_consent_by FROM \"" + schema + "\".clients")
                .forEach(r -> clients.put((UUID) r.get("id"), r));
        assertThat(clients.keySet()).containsExactlyInAnyOrder(c1, c2, c3);
        assertThat(clients.get(c1)).containsEntry("store_id", shopA).containsEntry("phone", shared)
                .containsEntry("phone_normalized", shared).containsEntry("client_type", "PERSON")
                .containsEntry("marketing_consent_by", owner);
        assertThat(clients.get(c1).get("birth_date").toString()).isEqualTo("1990-05-01");
        assertThat(clients.get(c2)).containsEntry("store_id", shopB).containsEntry("name", shared)
                .containsEntry("email", null).containsEntry("birth_date", null)
                .containsEntry("marketing_consent_by", null);
        assertThat(clients.get(c2).get("marketing_consent_at")).isNotNull();
        assertThat(clients.get(c3)).containsEntry("phone", "+237699000222").containsEntry("client_type", "COMPANY")
                .containsEntry("niu", "N".repeat(50)).containsEntry("marketing_consent_by", seller);
        assertThat(jdbc.queryForList("SELECT share_clients FROM \"" + schema + "\".stores", Boolean.class))
                .containsOnly(false);

        // ── re-run: nothing written
        int records = count("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ?", owner);
        JsonNode again = data(importOwner(owner, "DATABASE", token));
        assertThat(again.path("status").asText()).isEqualTo("COMPLETED");
        for (String key : List.of("categoriesCreated", "productsCreated", "productsMerged", "clientsCreated")) {
            assertThat(again.path("counts").path(key).asInt()).as(key).isZero();
        }
        assertThat(again.path("counts").path("alreadyImported").asInt()).isEqualTo(records);
        assertThat(count("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ?", owner))
                .isEqualTo(records);

        // ── partial re-run: a product and a customer added since
        UUID added = product(bUpper, "Huile", "nm:huile", null, "1200.00", false, null, false, null,
                T0.plusSeconds(11));
        UUID late = customer(shopB, "Client Tard", phone(), null, null, null, null, null, T0.plusSeconds(12));
        // a new live product named like the stored merged « Coca »: it never takes a stored name
        UUID newCoca = product(a, "COCA", "nm:coca nouveau", null, "200.00", false, null, false, null,
                T0.plusSeconds(13));
        JsonNode partial = data(importOwner(owner, "DATABASE", token));
        assertThat(partial.path("status").asText()).as(partial.toString()).isEqualTo("COMPLETED");
        assertThat(partial.path("counts").path("productsCreated").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT name FROM \"" + schema + "\".products WHERE id = ?", String.class,
                newCoca)).isEqualTo("COCA (2)");
        assertThat(jdbc.queryForObject("SELECT name FROM \"" + schema + "\".products WHERE id = ?", String.class,
                coca)).isEqualTo("Coca");
        assertThat(partial.path("notes").toString()).doesNotContain("PRODUCT_MERGED_IN_BREF"); // only new rows
        assertThat(partial.path("counts").path("clientsCreated").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"" + schema + "\".products WHERE id = ?", added)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"" + schema + "\".clients WHERE id = ? AND store_id = ?", late,
                shopB)).isEqualTo(1);

        // ── the export of the same owner: same products and customers (same canonical forms), nothing new
        writeExport(owner, null, null);
        BrefSnapshot fromDb = source(SourceKind.DATABASE).load(owner);
        BrefSnapshot fromExport = source(SourceKind.EXPORT).load(owner);
        assertThat(fromExport.products()).hasSize(9).containsExactlyInAnyOrderElementsOf(fromDb.products());
        assertThat(fromExport.customers()).hasSize(4).containsExactlyInAnyOrderElementsOf(fromDb.customers());
        JsonNode viaExport = data(importOwner(owner, "EXPORT", token));
        assertThat(viaExport.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(viaExport.path("counts").path("productsCreated").asInt()).isZero();
        assertThat(viaExport.path("counts").path("clientsCreated").asInt()).isZero();
    }

    // ── Story 24.7 — identity bridge ─────────────────────────────────────────

    /** A Bref account with every identity flag explicit (synthetic values). */
    private static UUID brefAccount(String role, String phone, boolean phoneVerified, String email,
                                    boolean emailVerified, String username, String shopId, boolean active,
                                    String provider, String socialId) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO \"user\" (id, email, password, \"firstName\", \"lastName\", phone, role, \"shopId\", "
                        + "\"isActive\", username, \"isVerified\", \"emailVerified\", provider, \"socialId\", "
                        + "\"createdAt\", \"updatedAt\") VALUES (?, ?, ?, 'Prénom', 'Nom', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?)",
                id, email, "owner".equals(role) ? null : HASH_SELLER, phone, role, shopId, active, username,
                phoneVerified, emailVerified, provider, socialId, utc(T0), utc(T0.plusSeconds(60)));
        return id;
    }

    /** A Keevo account (synthetic) with the given identifiers. */
    private UUID keevoAccount(String phone, String email, boolean emailVerified, String username, String hash) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, phone_number, "
                        + "email, email_verified_at, username, password_hash, role) VALUES (?, now(), now(), true, 0, ?, "
                        + "?, ?, ?, ?, 'OWNER')", id, phone, email, emailVerified ? Timestamp.from(T0) : null,
                username, hash);
        return id;
    }

    @Test
    @DisplayName("24.7 — owner already in Keevo by phone: Keevo account and hash kept, OWNER on the imported company "
            + "(two companies at login), Google identity brought; members: proven email linked, unconfirmed phone "
            + "invited, unproven email not carried; re-run changes nothing")
    void identityBridgeLinksInvitesAndReruns() throws Exception {
        String password = "Motdepasse-247";
        String keevoHash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password);
        String ownerPhone = phone();
        UUID keevoOwner = keevoAccount(ownerPhone, null, false, null, keevoHash);
        // the Keevo owner already runs a company
        String existingSchema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        TestAccess.grant(jdbc, keevoOwner, existingSchema, "OWNER");
        String managerEmail = "m" + tag() + "@example.test";
        UUID keevoManager = keevoAccount(phone(), managerEmail, true, null, null);
        String sellerPhone = phone();
        UUID keevoSeller = keevoAccount(sellerPhone, null, false, null, null);
        String typedEmail = "t" + tag() + "@example.test";
        keevoAccount(phone(), typedEmail, false, null, null);
        String googleSubject = "g" + tag();

        UUID owner = brefAccount("owner", ownerPhone, true, null, false, null, null, true, "google", googleSubject);
        UUID shop = shop(owner, "Pont " + tag(), "electronics", null, T0, null);
        UUID admin = brefAccount("admin", null, false, managerEmail, true, "a" + tag(), shop.toString(), true,
                "email", null);
        UUID invited = brefAccount("seller", sellerPhone, false, null, false, "i" + tag(), shop.toString(), true,
                "email", null);
        UUID fresh = brefAccount("viewer", null, false, typedEmail, false, "f" + tag(), shop.toString(), true,
                "email", null);
        String sleeperPhone = phone();
        UUID keevoSleeper = keevoAccount(sleeperPhone, null, false, null, null);
        UUID sleeper = brefAccount("seller", sleeperPhone, false, null, false, "z" + tag(), shop.toString(), false,
                "email", null);

        JsonNode batch = data(importOwner(owner, "DATABASE", adminToken()));
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        JsonNode counts = batch.path("counts");
        assertThat(counts.path("accountsLinked").asInt()).isEqualTo(2);
        assertThat(counts.path("membersInvited").asInt()).isEqualTo(1);
        assertThat(counts.path("usersCreated").asInt()).isEqualTo(1);
        assertThat(counts.path("membershipsCreated").asInt()).isEqualTo(3);
        assertThat(counts.path("employeesCreated").asInt()).isEqualTo(2);
        assertThat(counts.path("authenticatorsImported").asInt()).isEqualTo(1);
        assertThat(batch.path("notes").toString()).contains("OWNER_LINKED", "MEMBER_LINKED", "MEMBER_INVITED",
                "MEMBER_EMAIL_DROPPED", "MEMBER_INACTIVE_NOT_INVITED").doesNotContain("OWNER_SOCIAL_SIGN_IN");
        UUID tenantId = UUID.fromString(batch.path("tenantId").asText());

        // owner: Keevo account untouched, no account with the Bref id, OWNER on the imported company
        assertThat(jdbc.queryForObject("SELECT password_hash FROM public.users WHERE id = ?", String.class,
                keevoOwner)).isEqualTo(keevoHash);
        assertThat(count("SELECT count(*) FROM public.users WHERE id IN (?, ?, ?)", owner, admin, invited)).isZero();
        assertThat(jdbc.queryForObject("SELECT role FROM public.user_tenant_memberships WHERE user_id = ? AND "
                + "tenant_id = ?", String.class, keevoOwner, tenantId)).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("SELECT provider || ':' || subject FROM public.user_authenticators "
                + "WHERE user_id = ?", String.class, keevoOwner)).isEqualTo("GOOGLE:" + googleSubject);
        // two companies at login (19.6): the Keevo one and the imported one
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + ownerPhone + "\",\"password\":\"" + password + "\"}")).andReturn();
        assertThat(login.getResponse().getStatus()).as(login.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode session = objectMapper.readTree(login.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode memberships = session.has("data") ? session.path("data").path("memberships")
                : session.path("memberships");
        assertThat(memberships.size()).isEqualTo(2);

        // members
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class,
                tenantId);
        assertThat(jdbc.queryForObject("SELECT role FROM public.user_tenant_memberships WHERE user_id = ? AND "
                + "tenant_id = ?", String.class, keevoManager, tenantId)).isEqualTo("MANAGER");
        assertThat(jdbc.queryForMap("SELECT user_id, password_change_required FROM \"" + schema + "\".employees "
                + "WHERE id = ?", admin)).containsEntry("user_id", keevoManager)
                .containsEntry("password_change_required", false);
        assertThat(jdbc.queryForMap("SELECT status, identifier_kind, role FROM public.membership_invitations "
                + "WHERE tenant_id = ? AND user_id = ?", tenantId, keevoSeller))
                .containsEntry("status", "PENDING").containsEntry("identifier_kind", "PHONE")
                .containsEntry("role", "EMPLOYEE");
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE user_id = ? AND tenant_id = ?",
                keevoSeller, tenantId)).isZero();
        assertThat(jdbc.queryForObject("SELECT invited_by FROM public.membership_invitations WHERE tenant_id = ?",
                UUID.class, tenantId)).as("the owner invites, not the operator").isEqualTo(keevoOwner);
        // deactivated in Bref and already in Keevo: neither invited nor imported
        assertThat(count("SELECT count(*) FROM public.membership_invitations WHERE user_id = ?", keevoSleeper))
                .isZero();
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE user_id IN (?, ?)",
                keevoSleeper, sleeper)).isZero();
        assertThat(jdbc.queryForMap("SELECT email, username IS NOT NULL AS has_username FROM public.users "
                + "WHERE id = ?", fresh)).containsEntry("has_username", true).containsEntry("email", null);

        // records: linked accounts never under « users » (a rollback must not delete them)
        assertThat(count("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ? AND target_table = "
                + "'linked_users'", owner)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ? AND target_table = "
                + "'users' AND target_id IN (?, ?, ?)", owner, keevoOwner, keevoManager, keevoSeller)).isZero();
        assertThat(count("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ? AND target_table IN "
                + "('membership_invitations', 'user_authenticators')", owner)).isEqualTo(2);

        // re-run: nothing written, the records decide (not the current Keevo identifiers): the linked manager's
        // account changed meanwhile, which a fresh decision would refuse (LINK_TARGET_UNAVAILABLE)
        jdbc.update("UPDATE public.users SET is_active = false WHERE id = ?", keevoManager);
        JsonNode again = data(importOwner(owner, "DATABASE", adminToken()));
        assertThat(again.path("status").asText()).as(again.toString()).isEqualTo("COMPLETED");
        for (String key : List.of("tenantsCreated", "usersCreated", "membershipsCreated", "employeesCreated",
                "accountsLinked", "membersInvited", "authenticatorsImported")) {
            assertThat(again.path("counts").path(key).asInt()).as(key).isZero();
        }
        assertThat(again.path("counts").path("alreadyImported").asInt()).isPositive();
        assertThat(again.path("cases").size()).isZero();
        assertThat(count("SELECT count(*) FROM public.membership_invitations WHERE tenant_id = ?", tenantId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("24.7 — linked owner whose Keevo account already has another Google identity: kept, the Bref one is "
            + "not added (note)")
    void socialIdentityNotReplaced() throws Exception {
        String ownerPhone = phone();
        UUID keevoOwner = keevoAccount(ownerPhone, null, false, null, null);
        String keevoSubject = "k" + tag();
        jdbc.update("INSERT INTO public.user_authenticators (id, user_id, provider, subject) VALUES (?, ?, 'GOOGLE', ?)",
                UUID.randomUUID(), keevoOwner, keevoSubject);
        UUID owner = brefAccount("owner", ownerPhone, true, null, false, null, null, true, "google", "b" + tag());
        shop(owner, "Google " + tag(), "electronics", null, T0, null);

        JsonNode batch = data(importOwner(owner, "DATABASE", adminToken()));
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        assertThat(batch.path("counts").path("accountsLinked").asInt()).isEqualTo(1);
        assertThat(batch.path("counts").path("authenticatorsImported").asInt()).isZero();
        assertThat(batch.path("notes").toString()).contains("SOCIAL_NOT_LINKED");
        assertThat(jdbc.queryForList("SELECT subject FROM public.user_authenticators WHERE user_id = ?", String.class,
                keevoOwner)).containsExactly(keevoSubject);
    }

    @Test
    @DisplayName("24.7 — owner's phone in Keevo but not confirmed in Bref, member username taken, username equal to "
            + "a Keevo phone: blocked with the cases, nothing written; unknown owner 404")
    void collisionBlocksBeforeAnyWrite() throws Exception {
        String taken = phone();
        keevoAccount(taken, null, false, null, null);
        String usedUsername = "u" + tag();
        keevoAccount(phone(), null, false, usedUsername, null);
        String keevoPhone = phone();
        keevoAccount(keevoPhone, null, false, null, null);
        UUID owner = brefAccount("owner", taken, false, null, false, null, null, true, "email", null);
        UUID shop = shop(owner, "Collision " + tag(), "services", null, T0, null);
        UUID clash = brefAccount("seller", null, false, null, false, usedUsername.toUpperCase(), shop.toString(),
                true, "email", null);
        UUID numeric = brefAccount("seller", null, false, null, false, keevoPhone.substring(4), shop.toString(),
                true, "email", null);
        UUID other = keevoAccount(phone(), null, false, null, null);
        String heldSubject = "h" + tag();
        jdbc.update("INSERT INTO public.user_authenticators (id, user_id, provider, subject) VALUES (?, ?, 'GOOGLE', ?)",
                UUID.randomUUID(), other, heldSubject);
        UUID social = brefAccount("seller", null, false, null, false, "s" + tag(), shop.toString(), true, "google",
                heldSubject);
        String phoneAsUsername = phone();
        keevoAccount(null, null, false, phoneAsUsername.substring(1), null);
        UUID phoneUsed = brefAccount("seller", phoneAsUsername.substring(1), false, null, false, null,
                shop.toString(), true, "email", null);
        String inactivePhone = phone();
        UUID inactiveKeevo = keevoAccount(inactivePhone, null, false, null, null);
        jdbc.update("UPDATE public.users SET is_active = false WHERE id = ?", inactiveKeevo);
        UUID toInactive = brefAccount("seller", inactivePhone, false, null, false, null, shop.toString(), true,
                "email", null);

        MvcResult r = importOwner(owner, "DATABASE", adminToken());
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode batch = data(r);
        assertThat(batch.path("status").asText()).isEqualTo("BLOCKED");
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        batch.path("cases").forEach(c -> cases.put(c.path("code").asText(), c.path("sourceId").asText()));
        assertThat(cases).containsEntry("IDENTITY_UNPROVEN", owner.toString())
                .containsEntry("USERNAME_TAKEN", clash.toString())
                .containsEntry("USERNAME_IS_PHONE", numeric.toString())
                .containsEntry("SOCIAL_TAKEN", social.toString())
                .containsEntry("ACCOUNT_EXISTS", phoneUsed.toString())
                .containsEntry("LINK_TARGET_UNAVAILABLE", toInactive.toString());
        assertThat(batch.path("cases").toString()).doesNotContain(taken, usedUsername, keevoPhone);
        assertThat(batch.path("tenantId").isNull()).isTrue();
        assertThat(count("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ?", owner)).isZero();
        assertThat(count("SELECT count(*) FROM public.users WHERE id = ?", owner)).isZero();

        MvcResult unknown = importOwner(UUID.randomUUID(), "DATABASE", adminToken());
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("MIGRATION_OWNER_NOT_FOUND");
        MvcResult badSource = importOwner(owner, "FTP", adminToken());
        assertThat(badSource.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("an export with an embedded logo: imported through the store logo use case and traced")
    void exportWithEmbeddedLogo() throws Exception {
        UUID owner = user("owner", phone(), null, null, null, null, true, false, null);
        UUID shop = shop(owner, "Logo " + tag(), "beauty", null, T0, null);
        writeExport(owner, shop, PNG);
        JsonNode batch = data(importOwner(owner, "EXPORT", adminToken()));
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        assertThat(batch.path("counts").path("logosImported").asInt()).isEqualTo(1);
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class,
                UUID.fromString(batch.path("tenantId").asText()));
        assertThat(jdbc.queryForObject("SELECT logo_path FROM \"" + schema + "\".stores WHERE id = ?", String.class,
                shop)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT sector_type FROM \"" + schema + "\".tenant_preferences", String.class))
                .isEqualTo("OTHER");
        assertThat(count("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ? AND target_table = "
                + "'store_logo'", owner)).isEqualTo(1);
        // re-run: the logo is not imported again
        assertThat(data(importOwner(owner, "EXPORT", adminToken())).path("counts").path("logosImported").asInt())
                .isZero();
    }

    @Test
    @DisplayName("a logo that is not an image: noted, the import still COMPLETED with its team; an altered export: 422")
    void badLogoAndAlteredExport() throws Exception {
        UUID owner = user("owner", phone(), null, null, null, null, true, false, null);
        UUID shop = shop(owner, "Image " + tag(), "food", null, T0, null);
        user("seller", null, null, "i" + tag(), HASH_SELLER, shop.toString(), true, false, null);
        writeExport(owner, shop, Base64.getEncoder().encodeToString("pas une image".getBytes(StandardCharsets.UTF_8)));
        JsonNode batch = data(importOwner(owner, "EXPORT", adminToken()));
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        assertThat(batch.path("counts").path("logosImported").asInt()).isZero();
        assertThat(batch.path("counts").path("employeesCreated").asInt()).isEqualTo(1);
        assertThat(batch.path("notes").toString()).contains("LOGO_FAILED");

        Files.writeString(EXPORT_DIR.resolve("owner-" + owner + ".json.sha256"), "0".repeat(64));
        MvcResult altered = importOwner(owner, "EXPORT", adminToken());
        assertThat(altered.getResponse().getStatus()).isEqualTo(422);
        assertThat(altered.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("MIGRATION_EXPORT_INVALID");
    }

    @Test
    @DisplayName("batches: one RUNNING run per owner (409), an interrupted run older than the delay is taken over")
    void batchLocking() throws Exception {
        UUID owner = UUID.randomUUID();
        var first = recordStore.startBatch(owner, SourceKind.DATABASE, adminId, java.time.Duration.ofHours(1));
        assertThat(first).isPresent();
        assertThat(recordStore.startBatch(owner, SourceKind.DATABASE, adminId, java.time.Duration.ofHours(1)))
                .isEmpty();
        UUID busy = user("owner", phone(), null, null, null, null, true, false, null);
        shop(busy, "Occupée " + tag(), "food", null, T0, null);
        var running = recordStore.startBatch(busy, SourceKind.DATABASE, adminId, java.time.Duration.ofHours(1));
        assertThat(running).isPresent();
        MvcResult conflict = importOwner(busy, "DATABASE", adminToken());
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("MIGRATION_ALREADY_RUNNING");

        jdbc.update("UPDATE public.migration_batches SET started_at = now() - interval '2 hours' WHERE id = ?",
                first.get());
        var next = recordStore.startBatch(owner, SourceKind.DATABASE, adminId, java.time.Duration.ofHours(1));
        assertThat(next).isPresent().get().isNotEqualTo(first.get());
        assertThat(jdbc.queryForMap("SELECT status, error_label FROM public.migration_batches WHERE id = ?",
                first.get())).containsEntry("status", "FAILED")
                .containsEntry("error_label", "Import interrompu (repris par un nouvel import)");
    }

    @Test
    @DisplayName("both routes refuse an owner (403)")
    void ownerRefused() throws Exception {
        String schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        String ownerToken = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), schema, "OWNER");
        assertThat(importOwner(UUID.randomUUID(), "DATABASE", ownerToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/api/v1/admin/migration/batches/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    // ── export writer (the contract's shape, built from the synthetic Bref rows) ──────────────────────────

    private BrefSourcePort source(SourceKind kind) {
        return sources.stream().filter(s -> s.kind() == kind).findFirst().orElseThrow();
    }

    /** Writes owner-<id>.json (+ .sha256) from the Bref rows; {@code logoShop} gets {@code logo} (base64) embedded. */
    private void writeExport(UUID owner, UUID logoShop, String logo) throws Exception {
        String json = bref.queryForObject("""
                SELECT json_build_object(
                  'formatVersion', 1, 'source', 'api-bref', 'exportedAt', '2026-11-10T08:00:00.000Z',
                  'owner', (SELECT to_jsonb(u) FROM "user" u WHERE u.id = ?),
                  'shops', COALESCE((SELECT jsonb_agg(to_jsonb(s) ORDER BY s."createdAt") FROM shop s
                                     WHERE s."userId" = ?), '[]'::jsonb),
                  'members', COALESCE((SELECT jsonb_agg(to_jsonb(u) ORDER BY u."createdAt") FROM "user" u
                                       WHERE u."shopId" IN (SELECT id::text FROM shop WHERE "userId" = ?)
                                         AND u.id <> ?), '[]'::jsonb),
                  'products', COALESCE((SELECT jsonb_agg(to_jsonb(p) || jsonb_build_object(
                                          'unitPrice', p."unitPrice"::text, 'totalQuantity', p."totalQuantity"::text,
                                          'totalRevenue', p."totalRevenue"::text) ORDER BY p."createdAt")
                                        FROM product p WHERE lower(btrim(p."shopId")) IN
                                          (SELECT id::text FROM shop WHERE "userId" = ?)), '[]'::jsonb),
                  'customers', COALESCE((SELECT jsonb_agg(to_jsonb(c) || jsonb_build_object(
                                           'totalSpent', c."totalSpent"::text) ORDER BY c."createdAt")
                                         FROM customer c WHERE c."shopId" IN
                                           (SELECT id FROM shop WHERE "userId" = ?)), '[]'::jsonb),
                  'invoices', '[]'::jsonb)::text
                """, String.class, owner, owner, owner, owner, owner, owner);
        if (logoShop != null) {
            JsonNode root = objectMapper.readTree(json);
            for (JsonNode s : root.path("shops")) {
                if (s.path("id").asText().equals(logoShop.toString())) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) s).putObject("photoData")
                            .put("contentType", "image/png").put("base64", logo);
                }
            }
            json = objectMapper.writeValueAsString(root);
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Path file = EXPORT_DIR.resolve("owner-" + owner + ".json");
        Files.write(file, bytes);
        Files.writeString(EXPORT_DIR.resolve("owner-" + owner + ".json.sha256"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "  " + file.getFileName()
                        + "\n");
        assertThat(Base64.getDecoder().decode(PNG)).isNotEmpty();
    }
}
