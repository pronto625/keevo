package com.keevo.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.SessionTokenIssuer;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.DeviceInfo;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 22.19 — the I/O matrix of the RN end-to-end corrections, replayed by API on the real HTTP chain and a real
 * PostgreSQL: registration closes the sign-up steps, a preferences read never writes, setup/complete runs the sector
 * onboarding once, report times in HH:mm, the owner's own name, initial stock in a store, online oversell refused,
 * offline oversell floored, a seller reads its store, a disabled account is told so, a late offline invoice is issued,
 * invoice stats count what is still owed.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Story 22.19 — corrections API e2e RN (full context)")
class Corrections2219FullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired SessionTokenIssuer issuer;
    @Autowired JwtTokenProvider jwt;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("X-App-Version", "3.0.0");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode json(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        return objectMapper.readTree(body(r));
    }

    private static String phone() {
        return "+2376" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
    }

    private record Owner(String schema, UUID tenantId, UUID ownerId, String token, UUID storeId) {
    }

    private Owner owner() {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        String schema = tenant.getSchemaName();
        UUID ownerId = UUID.randomUUID();
        TestAccess.grant(jdbc, ownerId, schema, "OWNER");
        String token = tx.execute(s -> issuer.issue(ownerId, tenant, "OWNER", null,
                DeviceInfo.of(UUID.randomUUID().toString(), "Redmi")).tokens()).accessToken();
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        return new Owner(schema, tenant.getId(), ownerId, token, storeId);
    }

    /** A member's account row (public.users). */
    private void account(UUID userId, String role) {
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                + "phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, ?)", userId, phone(), role);
    }

    private void enable(UUID tenantId, String moduleId) {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ? AND module_id = ?", tenantId, moduleId);
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, 'ACTIVE', "
                + "'FREE')", tenantId, moduleId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ── identity: register, preferences, setup, own name, disabled account ───────────────────────────────

    @Test
    @DisplayName("register closes the sign-up steps; prefs read writes nothing; setup/complete seeds the sector once; "
            + "HH:mm accepted; the owner renames himself")
    void signUpAndPreferences() throws Exception {
        String phone = phone();
        JsonNode reg = json(call(null, HttpMethod.POST, "/api/v1/auth/register",
                Map.of("phoneNumber", phone, "password", "Secret123!")), 201);
        String token = reg.path("token").asText();
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE code = ?", String.class,
                reg.path("tenantCode").asText());
        UUID tenantId = UUID.fromString(reg.path("tenantId").asText());
        assertThat(count("SELECT count(*) FROM public.tenants WHERE schema_name = ? AND setup_completed_at IS NOT NULL",
                schema)).isEqualTo(1);

        // D2 — a read never writes
        JsonNode prefs = json(call(token, HttpMethod.GET, "/api/v1/tenant/preferences", null), 200).path("data");
        assertThat(prefs.path("eodReportTime").asText()).isEqualTo("20:00");
        assertThat(count("SELECT count(*) FROM \"" + schema + "\".tenant_preferences")).isZero();

        // D2 — setup/complete: sector of the category, categories seeded, idempotent
        json(call(token, HttpMethod.PATCH, "/api/v1/account/company/profile",
                Map.of("category", "food", "designation", "Épicerie Aicha")), 200);
        // D9 — the company picker shows the company's name, never its code
        JsonNode login = json(call(null, HttpMethod.POST, "/api/v1/auth/login",
                Map.of("phoneNumber", phone, "password", "Secret123!")), 200);
        assertThat(login.path("memberships").get(0).path("tenantName").asText()).isEqualTo("Épicerie Aicha");
        json(call(token, HttpMethod.POST, "/api/v1/account/company/setup/complete", null), 200);
        json(call(token, HttpMethod.POST, "/api/v1/account/company/setup/complete", null), 200);
        assertThat(jdbc.queryForList("SELECT sector_type FROM \"" + schema + "\".tenant_preferences", String.class))
                .containsExactly("FOOD_GROCERY");
        assertThat(count("SELECT count(*) FROM \"" + schema + "\".categories")).isPositive();

        // D3 — report time as the GET gives it
        enable(tenantId, "report-config");
        Map<String, Object> put = new LinkedHashMap<>();
        put.put("eodReportEnabled", true);
        put.put("eodReportChannel", "WHATSAPP");
        put.put("eodReportTime", "18:00");
        put.put("weeklyReportEnabled", true);
        put.put("weeklyReportDay", 1);
        put.put("weeklyReportTime", "19:30");
        put.put("weeklyReportChannel", "WHATSAPP");
        put.put("inventoryReportEnabled", false);
        put.put("inventoryReportChannel", "WHATSAPP");
        put.put("stockAlertEnabled", true);
        put.put("stockAlertChannel", "PUSH");
        json(call(token, HttpMethod.PUT, "/api/v1/tenant/report-preferences", put), 200);
        assertThat(jdbc.queryForObject("SELECT eod_report_time::text FROM \"" + schema + "\".tenant_preferences",
                String.class)).isEqualTo("18:00:00");

        // D17 — the owner's own name
        JsonNode profile = json(call(token, HttpMethod.PUT, "/api/v1/auth/profile",
                Map.of("firstName", " Aicha ", "lastName", "Mbarga")), 200);
        assertThat(profile.path("firstName").asText()).isEqualTo("Aicha");
        assertThat(jdbc.queryForObject("SELECT first_name || ' ' || last_name FROM public.users WHERE id = ?",
                String.class, UUID.fromString(reg.path("userId").asText()))).isEqualTo("Aicha Mbarga");
        assertThat(count("SELECT count(*) FROM \"" + schema + "\".audit_log WHERE action = 'PROFILE_UPDATED'"))
                .isEqualTo(1);
        // D16 — the history speaks French
        JsonNode audit = json(call(token, HttpMethod.GET, "/api/v1/audit?action=PROFILE_UPDATED", null), 200)
                .path("data");
        assertThat(audit.path("entries").get(0).path("actionLabel").asText()).isEqualTo("Profil modifié");
    }

    @Test
    @DisplayName("D13 — an account whose only membership is deactivated is told so at login (403 ACCOUNT_DISABLED)")
    void disabledAccount() throws Exception {
        String phone = phone();
        JsonNode reg = json(call(null, HttpMethod.POST, "/api/v1/auth/register",
                Map.of("phoneNumber", phone, "password", "Secret123!")), 201);
        jdbc.update("UPDATE public.user_tenant_memberships SET is_active = false WHERE user_id = ?",
                UUID.fromString(reg.path("userId").asText()));

        JsonNode refused = json(call(null, HttpMethod.POST, "/api/v1/auth/login",
                Map.of("phoneNumber", phone, "password", "Secret123!")), 403);
        assertThat(refused.path("domainCode").asText()).isEqualTo("ACCOUNT_DISABLED");
        assertThat(refused.path("error").asText())
                .isEqualTo("Votre compte a été désactivé. Contactez le propriétaire de la boutique.");
    }

    // ── stock: initial stock, online oversell, offline oversell, per-store read ──────────────────────────

    @Test
    @DisplayName("D11/D19/D20 — initial quantity enters the default store; online oversell 422; offline oversell "
            + "applied and floored at 0 with a warning; legacy counter follows")
    void stock() throws Exception {
        Owner o = owner();
        JsonNode product = json(call(o.token(), HttpMethod.POST, "/api/v1/products",
                Map.of("name", "Riz 5 kg", "price", 1000, "stockQuantity", 3)), 201).path("data");
        UUID productId = UUID.fromString(product.path("id").asText());
        String levels = "SELECT quantity FROM \"" + o.schema() + "\".stock_levels WHERE product_id = ? AND store_id = ?";
        String counter = "SELECT stock_quantity FROM \"" + o.schema() + "\".products WHERE id = ?";
        assertThat(jdbc.queryForObject(levels, Integer.class, productId, o.storeId())).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM \"" + o.schema() + "\".stock_movements WHERE product_id = ? "
                + "AND notes = 'Stock initial'", productId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(counter, Integer.class, productId)).isEqualTo(3);

        // D19 — the store's own level, not the legacy counter (made different on purpose); D20 — a photo flag
        jdbc.update("UPDATE \"" + o.schema() + "\".products SET stock_quantity = 99, photo_url = 'https://x/p.png' "
                + "WHERE id = ?", productId);
        JsonNode perStore = json(call(o.token(), HttpMethod.GET, "/api/v1/products?storeId=" + o.storeId(), null),
                200).path("data").get(0);
        assertThat(perStore.has("storeStockQuantity")).isTrue();
        assertThat(perStore.path("storeStockQuantity").asInt()).isEqualTo(3);
        assertThat(perStore.path("stockQuantity").asInt()).isEqualTo(99);
        assertThat(perStore.path("hasPhoto").asBoolean()).isTrue();
        jdbc.update("UPDATE \"" + o.schema() + "\".products SET stock_quantity = 3, photo_url = NULL WHERE id = ?",
                productId);

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("itemId", UUID.randomUUID().toString());
        line.put("productId", productId.toString());
        line.put("productName", "Riz 5 kg");
        line.put("catalogueUnitPrice", 1000);
        line.put("appliedUnitPrice", 1000);
        line.put("quantity", 5);

        // online: refused in French with what is left
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("saleId", UUID.randomUUID().toString());
        sale.put("paymentMode", "CASH");
        sale.put("storeId", o.storeId().toString());
        sale.put("items", List.of(line));
        // the existing V1 status of INSUFFICIENT_STOCK (422) is kept; the French text names what is left
        JsonNode refused = json(call(o.token(), HttpMethod.POST, "/api/v1/sales", sale), 422);
        assertThat(refused.path("domainCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(refused.path("error").asText()).isEqualTo("Stock insuffisant dans cette boutique : il reste 3");

        // offline (sync push): applied, floored at 0, French warning
        Map<String, Object> payload = new LinkedHashMap<>(sale);
        payload.put("saleId", UUID.randomUUID().toString());
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", "CREATE_SALE");
        op.put("entityId", payload.get("saleId"));
        op.put("payload", payload);
        op.put("clientTimestamp", Instant.now().minusSeconds(60).toString());
        JsonNode pushed = json(call(o.token(), HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", UUID.randomUUID().toString(), "operations", List.of(op))), 200);
        JsonNode result = pushed.path("data").path("results").get(0);
        assertThat(result.path("status").asText()).as(result.toString()).isEqualTo("APPLIED");
        assertThat(result.path("conflictData").path("conflictType").asText()).isEqualTo("STOCK_FLOORED");
        assertThat(result.path("conflictData").path("lines").get(0).path("missingQuantity").asInt()).isEqualTo(2);
        assertThat(result.path("conflictData").path("warning").asText()).contains("Stock insuffisant").contains("2");
        assertThat(jdbc.queryForObject(levels, Integer.class, productId, o.storeId())).isZero();
        assertThat(count("SELECT count(*) FROM \"" + o.schema() + "\".stock_movements WHERE product_id = ? "
                + "AND movement_type = 'ADJUSTMENT' AND quantity_change = 2", productId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(counter, Integer.class, productId)).isZero();

        // per-store read, photo flag
        JsonNode list = json(call(o.token(), HttpMethod.GET, "/api/v1/products?storeId=" + o.storeId(), null), 200)
                .path("data");
        assertThat(list.get(0).path("storeStockQuantity").asInt()).isZero();
        assertThat(list.get(0).path("hasPhoto").asBoolean()).isFalse();
        json(call(o.token(), HttpMethod.GET, "/api/v1/stock/stores/" + o.storeId() + "/products", null), 200);
    }

    @Test
    @DisplayName("D18 — CANCEL_SALE from the till cancels a completed sale of the day (stock back); twice → French "
            + "« Vente déjà annulée »")
    void cancelSaleOfTheDay() throws Exception {
        Owner o = owner();
        UUID productId = UUID.fromString(json(call(o.token(), HttpMethod.POST, "/api/v1/products",
                Map.of("name", "Savon", "price", 500, "stockQuantity", 5)), 201).path("data").path("id").asText());
        UUID saleId = UUID.randomUUID();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("itemId", UUID.randomUUID().toString());
        line.put("productId", productId.toString());
        line.put("productName", "Savon");
        line.put("catalogueUnitPrice", 500);
        line.put("appliedUnitPrice", 500);
        line.put("quantity", 2);
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("saleId", saleId.toString());
        sale.put("paymentMode", "CASH");
        sale.put("storeId", o.storeId().toString());
        sale.put("items", List.of(line));
        assertThat(call(o.token(), HttpMethod.POST, "/api/v1/sales", sale).getResponse().getStatus()).isEqualTo(201);
        String levels = "SELECT quantity FROM \"" + o.schema() + "\".stock_levels WHERE product_id = ? AND store_id = ?";
        assertThat(jdbc.queryForObject(levels, Integer.class, productId, o.storeId())).isEqualTo(3);

        JsonNode first = pushOne(o, "CANCEL_SALE", saleId, Map.of("saleId", saleId.toString()));
        assertThat(first.path("status").asText()).as(first.toString()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT status FROM \"" + o.schema() + "\".sales WHERE id = ?", String.class,
                saleId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(levels, Integer.class, productId, o.storeId())).isEqualTo(5);

        JsonNode again = pushOne(o, "CANCEL_SALE", saleId, Map.of("saleId", saleId.toString()));
        assertThat(again.path("status").asText()).isEqualTo("REJECTED");
        assertThat(again.path("conflictData").path("message").asText()).isEqualTo("Vente déjà annulée");
    }

    private JsonNode pushOne(Owner o, String type, UUID entityId, Map<String, Object> payload) throws Exception {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        op.put("entityId", entityId.toString());
        op.put("payload", payload);
        op.put("clientTimestamp", Instant.now().toString());
        return json(call(o.token(), HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", UUID.randomUUID().toString(), "operations", List.of(op))), 200)
                .path("data").path("results").get(0);
    }

    @Test
    @DisplayName("D12 — a seller reads its own store, not another one")
    void sellerStore() throws Exception {
        Owner o = owner();
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + o.schema() + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Etoundi', false)", sellerId, o.storeId());
        String seller = TestAccess.token(jdbc, jwt, sellerId, o.schema(), "EMPLOYEE", "ACTIVE", o.storeId(), false);

        JsonNode own = json(call(seller, HttpMethod.GET, "/api/v1/stores/" + o.storeId(), null), 200).path("data");
        assertThat(own.path("id").asText()).isEqualTo(o.storeId().toString());
        json(call(seller, HttpMethod.GET, "/api/v1/stores/" + UUID.randomUUID(), null), 403);
        // D19 — another store's stock: refused for a member with a store
        json(call(seller, HttpMethod.GET, "/api/v1/products?storeId=" + UUID.randomUUID(), null), 403);
        json(call(seller, HttpMethod.GET, "/api/v1/stock/stores/" + UUID.randomUUID() + "/products", null), 403);
        json(call(seller, HttpMethod.GET, "/api/v1/products?storeId=" + o.storeId(), null), 200);

        // D17 — a member (and a viewer) renames himself
        account(sellerId, "EMPLOYEE");
        JsonNode renamed = json(call(seller, HttpMethod.PUT, "/api/v1/auth/profile",
                Map.of("firstName", "Paul", "lastName", "Etoundi Junior")), 200);
        assertThat(renamed.path("lastName").asText()).isEqualTo("Etoundi Junior");
        assertThat(jdbc.queryForObject("SELECT last_name FROM \"" + o.schema() + "\".employees WHERE user_id = ?",
                String.class, sellerId)).isEqualTo("Etoundi Junior");
        UUID viewerId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + o.schema() + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Awa', 'K', false)", viewerId, o.storeId());
        account(viewerId, "EMPLOYEE");
        String viewer = TestAccess.token(jdbc, jwt, viewerId, o.schema(), "VIEWER", "ACTIVE", o.storeId(), false);
        json(call(viewer, HttpMethod.PUT, "/api/v1/auth/profile", Map.of("firstName", "Awa", "lastName", "Kamga")),
                200);
    }

    @Test
    @DisplayName("D7 — a role without « Voir les devis et factures » reads neither the stats nor the top products")
    void statsNeedInvoiceView() throws Exception {
        Owner o = owner();
        enable(o.tenantId(), "invoicing");
        UUID memberId = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + o.schema() + "\".employees (id, user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, ?, 'Caisse', 'Seule', false)", employee, memberId,
                o.storeId());
        String member = TestAccess.token(jdbc, jwt, memberId, o.schema(), "EMPLOYEE", "ACTIVE", o.storeId(), false);
        Map<String, Object> role = new LinkedHashMap<>();
        role.put("name", "Caisse seule");
        role.put("baseRole", "EMPLOYEE");
        role.put("permissions", List.of("SALE_CREATE"));
        role.put("storeIds", List.of(o.storeId()));
        String roleId = json(call(o.token(), HttpMethod.POST, "/api/v1/roles", role), 201).path("data").path("id")
                .asText();
        json(call(o.token(), HttpMethod.PUT, "/api/v1/employees/" + employee + "/custom-role",
                Map.of("roleId", roleId)), 200);

        json(call(member, HttpMethod.GET, "/api/v1/invoices/stats", null), 403);
        json(call(member, HttpMethod.GET, "/api/v1/invoices/top-products", null), 403);
        json(call(o.token(), HttpMethod.GET, "/api/v1/invoices/stats", null), 200);
    }

    // ── invoicing: late offline invoice, stats ───────────────────────────────────────────────────────────

    private UUID draft(Owner o, long unitPrice, LocalDate issueDate) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", Map.of("name", "Client Awa"));
        body.put("lines", List.of(Map.of("description", "Riz", "quantity", 1, "unitPrice", unitPrice)));
        if (issueDate != null) body.put("issueDate", issueDate.toString());
        return UUID.fromString(json(call(o.token(), HttpMethod.POST, "/api/v1/invoices", body), 201)
                .path("data").path("id").asText());
    }

    @Test
    @DisplayName("D4 — an invoice dated before the latest issued one is issued on that latest day, the requested day "
            + "kept; D5 — stats count what is still owed")
    void invoicing() throws Exception {
        LocalDate today = LocalDate.now(KeevoZones.WAT);
        Assumptions.assumeTrue(today.getDayOfYear() > 1, "needs a yesterday in the same year");
        Owner o = owner();
        enable(o.tenantId(), "invoicing");
        UUID first = draft(o, 2500, null);
        json(call(o.token(), HttpMethod.POST, "/api/v1/invoices/" + first + "/issue", null), 200);
        UUID late = draft(o, 1000, today.minusDays(1));
        // an offline draft whose due date was its requested day: the due date follows the moved issue day
        jdbc.update("UPDATE \"" + o.schema() + "\".invoices SET due_date = ? WHERE id = ?", today.minusDays(1), late);
        JsonNode issued = json(call(o.token(), HttpMethod.POST, "/api/v1/invoices/" + late + "/issue", null), 200)
                .path("data");
        assertThat(issued.path("issueDate").asText()).isEqualTo(today.toString());
        assertThat(issued.path("dueDate").asText()).isEqualTo(today.toString());
        assertThat(issued.path("requestedIssueDate").asText()).isEqualTo(today.minusDays(1).toString());

        // a deposit of 1 000 on the 2 500 invoice: still owed 1 500 + 1 000
        json(call(o.token(), HttpMethod.POST, "/api/v1/invoices/" + first + "/payments",
                Map.of("amount", 1000, "paidAt", today.toString())), 201);
        JsonNode stats = json(call(o.token(), HttpMethod.GET, "/api/v1/invoices/stats", null), 200).path("data");
        long ttcFirst = jdbc.queryForObject("SELECT total_ttc FROM \"" + o.schema() + "\".invoices WHERE id = ?",
                Long.class, first);
        long ttcLate = jdbc.queryForObject("SELECT total_ttc FROM \"" + o.schema() + "\".invoices WHERE id = ?",
                Long.class, late);
        assertThat(stats.path("totalPending").asLong()).isEqualTo(ttcFirst - 1000 + ttcLate);
        assertThat(stats.path("totalAmount").asLong()).isEqualTo(ttcFirst + ttcLate);
    }

    @Test
    @DisplayName("D5 — after a partial credit note and a deposit: totalAmount net of the credit, totalPending = what "
            + "is still owed")
    void statsAfterPartialCreditNote() throws Exception {
        Owner o = owner();
        enable(o.tenantId(), "invoicing");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", Map.of("name", "Client Awa"));
        body.put("lines", List.of(Map.of("description", "Riz", "quantity", 4, "unitPrice", 1000)));
        UUID id = UUID.fromString(json(call(o.token(), HttpMethod.POST, "/api/v1/invoices", body), 201)
                .path("data").path("id").asText());
        json(call(o.token(), HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        json(call(o.token(), HttpMethod.POST, "/api/v1/invoices/" + id + "/credit-notes",
                Map.of("reason", "Un sac rendu", "lines", List.of(Map.of("position", 1, "quantity", 1)))), 201);
        json(call(o.token(), HttpMethod.POST, "/api/v1/invoices/" + id + "/payments",
                Map.of("amount", 500, "paidAt", LocalDate.now(KeevoZones.WAT).toString())), 201);

        long ttc = jdbc.queryForObject("SELECT total_ttc FROM \"" + o.schema() + "\".invoices WHERE id = ?",
                Long.class, id);
        long credited = jdbc.queryForObject("SELECT COALESCE(SUM(total_ttc), 0) FROM \"" + o.schema()
                + "\".credit_notes WHERE invoice_id = ?", Long.class, id);
        assertThat(credited).isPositive().isLessThan(ttc);
        JsonNode stats = json(call(o.token(), HttpMethod.GET, "/api/v1/invoices/stats", null), 200).path("data");
        assertThat(stats.path("totalAmount").asLong()).isEqualTo(ttc - credited);
        assertThat(stats.path("totalPending").asLong()).isEqualTo(ttc - credited - 500);
    }

    @Test
    @DisplayName("D21 — signatures sent with the invoice are stored and returned as data URLs; D18 setup: none")
    void invoiceSignatures() throws Exception {
        Owner o = owner();
        enable(o.tenantId(), "invoicing");
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(40, 20,
                java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", png);
        String dataUrl = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png.toByteArray());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", Map.of("name", "Client Awa"));
        body.put("lines", List.of(Map.of("description", "Riz", "quantity", 1, "unitPrice", 1000)));
        body.put("sellerSignature", dataUrl);

        JsonNode created = json(call(o.token(), HttpMethod.POST, "/api/v1/invoices", body), 201).path("data");
        assertThat(created.path("sellerSigned").asBoolean()).isTrue();
        assertThat(created.path("clientSigned").asBoolean()).isFalse();
        assertThat(created.path("sellerSignature").asText()).startsWith("data:image/png;base64,");
        assertThat(created.has("clientSignature")).isFalse();
        JsonNode read = json(call(o.token(), HttpMethod.GET, "/api/v1/invoices/" + created.path("id").asText(),
                null), 200).path("data");
        assertThat(read.path("sellerSignature").asText()).startsWith("data:image/png;base64,");
        // saved again with the same image: not re-signed (one signature audit line)
        json(call(o.token(), HttpMethod.PUT, "/api/v1/invoices/" + created.path("id").asText(), body), 200);
        assertThat(count("SELECT count(*) FROM \"" + o.schema() + "\".audit_log WHERE action = 'INVOICE_SIGNED'"))
                .isEqualTo(1);

        body.put("sellerSignature", "data:image/jpeg;base64,AAAA");
        JsonNode refused = json(call(o.token(), HttpMethod.POST, "/api/v1/invoices", body), 422);
        assertThat(refused.path("error").asText()).isEqualTo("La signature doit être une image PNG de 200 Ko au plus");
    }
}
