package com.keevo.shared.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase.CreateProductDto;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.10a — the Free / Premium classification of plan §6.1 enforced on the real HTTP chain (production
 * security filter, real JWTs, entitlement aspect, {@code public.tenant_modules} + the tenant's {@code subscriptions}
 * on PostgreSQL): every REST row of the spec's I/O matrix. The tenant is created by the production
 * {@link TenantFactory} (registration trial: PREMIUM_TRIAL, 6 months) and turned Free / expired by updating its
 * own {@code subscriptions} row; module rows are inserted directly (the lifecycle is tested elsewhere).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Premium gating of the REST endpoints — full context (Story 13.10a)")
class PremiumGatingFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired CreateProductUseCase createProduct;
    @Autowired TenantPreferencesRepository preferencesRepository;
    @Autowired List<com.keevo.shared.application.port.export.ExportDatasetProvider> exportDatasets;
    @Autowired List<com.keevo.shared.application.port.sync.DeltaEntityProvider> deltaProviders;

    private final UUID ownerId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private UUID storeId;
    private UUID productId;
    private UUID secondStoreId;
    private String token;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        // registration materialises the crm row (13.9): start from "no row" so the transition rule decides
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        TenantContext.setCurrentTenant(schema);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                ownerId, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        try {
            productId = createProduct.execute(new CreateProductDto("Savon gating", null, null, null, 500, null,
                    null, 0, ownerId, 0, null, "OWNER", null, null, null)).getId();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
        token = jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER");
        // what a real (onboarded) tenant has: preferences and stock in its store — so valid premium calls are 2xx
        TenantContext.setCurrentTenant(schema);
        try {
            preferencesRepository.save(TenantPreferences.withDefaults(UUID.randomUUID(), SectorType.FOOD_GROCERY,
                    "21:00:00", false, java.time.Instant.now()));
        } finally {
            TenantContext.clear();
        }
        if (jdbc.update("UPDATE \"" + schema + "\".stock_levels SET quantity = 50 WHERE product_id = ? AND store_id = ?",
                productId, storeId) == 0) {
            jdbc.update("INSERT INTO \"" + schema + "\".stock_levels (product_id, store_id, quantity) VALUES (?, ?, 50)",
                    productId, storeId);
        }
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private void freePlan() {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE', max_stores = 1, "
                + "max_products = 500, max_employees = 2, expires_at = NULL");
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenantId);
    }

    private void moduleRow(String moduleId, String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, ?)",
                tenantId, moduleId, status, "ACTIVE".equals(status) ? "PREMIUM" : "FREE");
    }

    private MvcResult call(HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    /** A second store, created while the tenant is still on its trial (unlimited stores). */
    private void secondStore() throws Exception {
        MvcResult r = call(HttpMethod.POST, "/api/v1/stores", Map.of("name", "Boutique Akwa"));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        secondStoreId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores WHERE id <> ? LIMIT 1",
                UUID.class, storeId);
    }

    /** Premium writes of the matrix, with valid bodies (validation runs before the gate). */
    private static final List<String> WRITES = List.of("transfer", "counting session", "weekly report", "report config");

    /** The premium endpoints of the matrix, with valid bodies (validation runs before the gate). */
    private Map<String, MvcResult> premiumCalls() throws Exception {
        Map<String, MvcResult> out = new LinkedHashMap<>();
        out.put("transfer", call(HttpMethod.POST, "/api/v1/stock/transfers", Map.of("sourceStoreId", storeId,
                "destinationStoreId", secondStoreId != null ? secondStoreId : UUID.randomUUID(),
                "productId", productId, "quantity", 1)));
        out.put("multi-store overview", call(HttpMethod.GET, "/api/v1/stock/overview", null));
        out.put("counting session", call(HttpMethod.POST, "/api/v1/inventory/sessions",
                Map.of("storeId", storeId, "scope", "FULL")));
        out.put("weekly report", call(HttpMethod.POST, "/api/v1/reports/trigger-weekly", null));
        out.put("leaderboard", call(HttpMethod.GET, "/api/v1/reporting/leaderboard", null));
        out.put("profitability", call(HttpMethod.GET, "/api/v1/reporting/profitability/products", null));
        out.put("export xlsx", call(HttpMethod.POST, "/api/v1/exports",
                Map.of("datasets", List.of("stores"), "format", "XLSX")));
        out.put("report config", call(HttpMethod.PUT, "/api/v1/tenant/report-preferences", reportPreferences()));
        return out;
    }

    private static Map<String, Object> reportPreferences() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eodReportEnabled", true);
        body.put("eodReportChannel", "WHATSAPP");
        body.put("weeklyReportEnabled", true);
        body.put("weeklyReportDay", 0);
        body.put("weeklyReportTime", "20:00:00");
        body.put("weeklyReportChannel", "WHATSAPP");
        body.put("inventoryReportEnabled", false);
        body.put("inventoryReportChannel", "WHATSAPP");
        body.put("stockAlertEnabled", false);
        body.put("stockAlertChannel", "PUSH");
        body.put("eodReportTime", "21:00:00");
        return body;
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static void assertNotEntitled(String what, MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(what + " status — " + body(r)).isEqualTo(403);
        assertThat(body(r)).as(what).contains("\"domainCode\":\"MODULE_NOT_ENTITLED\"")
                .contains("Ce module n'est pas inclus dans votre abonnement");
    }

    private static void assertSuccess(String what, MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(what + " — " + body(r)).isBetween(200, 299);
    }

    private static void assertReadOnly(String what, MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(what + " status — " + body(r)).isEqualTo(403);
        assertThat(body(r)).as(what).contains("\"domainCode\":\"MODULE_READ_ONLY\"");
    }

    private void expire(String plan, String interval) {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = '" + plan + "', "
                + "expires_at = now() - interval '" + interval + "'");
    }

    private static void assertPassesGate(String what, MvcResult r) throws Exception {
        assertThat(body(r)).as(what + " — " + r.getResponse().getStatus())
                .doesNotContain("MODULE_NOT_ENTITLED").doesNotContain("MODULE_READ_ONLY");
        // past the gate the handler answers as before (a business error such as missing preferences included)
        assertThat(r.getResponse().getStatus()).as(what + " status — " + body(r)).isNotIn(401, 403);
    }

    // ── matrix ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Free tenant: transfer, multi-store, counting, weekly report, leaderboard, profitability, XLSX export, report config → 403 MODULE_NOT_ENTITLED")
    void freeTenant_premiumEndpointsRefused() throws Exception {
        freePlan();
        for (var e : premiumCalls().entrySet()) {
            assertNotEntitled(e.getKey(), e.getValue());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".inventory_sessions", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("Free tenant: products, clients, sales history, dashboard, CSV export, end-of-day report as today")
    void freeTenant_freeEndpointsUnchanged() throws Exception {
        freePlan();
        assertThat(call(HttpMethod.GET, "/api/v1/products", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/clients", null).getResponse().getStatus()).isEqualTo(200);
        MvcResult client = call(HttpMethod.POST, "/api/v1/clients", Map.of("name", "Awa Free", "phone", "+237690000123"));
        assertThat(client.getResponse().getStatus()).as(body(client)).isIn(200, 201);
        assertThat(call(HttpMethod.GET, "/api/v1/dashboard/summary", null).getResponse().getStatus()).isEqualTo(200);
        assertPassesGate("products/{id}/stock", call(HttpMethod.GET, "/api/v1/products/" + productId + "/stock", null));
        assertPassesGate("sales history", call(HttpMethod.GET, "/api/v1/sales/history", null));
        assertPassesGate("day closure", call(HttpMethod.GET, "/api/v1/day-closures", null));
        assertPassesGate("trigger daily", call(HttpMethod.POST, "/api/v1/reports/trigger-daily", null));
        MvcResult csv = call(HttpMethod.POST, "/api/v1/exports", Map.of("datasets", List.of("stores"), "format", "CSV"));
        assertThat(csv.getResponse().getStatus()).as(body(csv)).isEqualTo(200);
    }

    @Test
    @DisplayName("Free tenant: a second store is still PLAN_LIMIT_EXCEEDED (plan limit, not a module)")
    void freeTenant_secondStore_planLimit() throws Exception {
        freePlan();
        MvcResult r = call(HttpMethod.POST, "/api/v1/stores", Map.of("name", "Deuxième boutique"));
        assertThat(body(r)).as("status " + r.getResponse().getStatus()).contains("PLAN_LIMIT_EXCEEDED")
                .doesNotContain("MODULE_NOT_ENTITLED");
    }

    @Test
    @DisplayName("Premium trial (registration): every premium endpoint answers 2xx")
    void premiumTrial_everythingPasses() throws Exception {
        secondStore();
        for (var e : premiumCalls().entrySet()) {
            assertSuccess(e.getKey(), e.getValue());
        }
    }

    @Test
    @DisplayName("Premium (paid) tenant: every premium endpoint answers 2xx")
    void premium_everythingPasses() throws Exception {
        secondStore();
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'PREMIUM', "
                + "expires_at = now() + interval '30 days'");
        for (var e : premiumCalls().entrySet()) {
            assertSuccess(e.getKey(), e.getValue());
        }
    }

    @Test
    @DisplayName("Trial ended 1 min ago (nightly job not run) → read-only grace: reads and the export 2xx, writes 403 MODULE_READ_ONLY")
    void trialExpired_readOnlyGrace() throws Exception {
        secondStore();
        expire("PREMIUM_TRIAL", "1 minute");
        for (var e : premiumCalls().entrySet()) {
            if (WRITES.contains(e.getKey())) {
                assertReadOnly(e.getKey(), e.getValue());
            } else {
                assertSuccess(e.getKey(), e.getValue());
            }
        }
    }

    @Test
    @DisplayName("Premium ended 8 days ago → grace over: every premium endpoint 403 MODULE_NOT_ENTITLED")
    void premiumExpired_graceOver_refused() throws Exception {
        expire("PREMIUM", "8 days");
        for (var e : premiumCalls().entrySet()) {
            assertNotEntitled(e.getKey(), e.getValue());
        }
    }

    @Test
    @DisplayName("Free tenant: premium data never leaves through free paths — CSV export of counts / transfers 403, pull without counting / transfers")
    void freeTenant_premiumDataNotLeaked() throws Exception {
        secondStore();
        assertSuccess("session while on trial", call(HttpMethod.POST, "/api/v1/inventory/sessions",
                Map.of("storeId", storeId, "scope", "FULL")));
        assertSuccess("transfer while on trial", call(HttpMethod.POST, "/api/v1/stock/transfers",
                Map.of("sourceStoreId", storeId, "destinationStoreId", secondStoreId, "productId", productId,
                        "quantity", 1)));
        freePlan();

        for (String dataset : List.of("inventory_sessions", "inventory_counts", "stock_transfers")) {
            assertNotEntitled("CSV " + dataset, call(HttpMethod.POST, "/api/v1/exports",
                    Map.of("datasets", List.of(dataset), "format", "CSV")));
        }
        MvcResult pull = call(HttpMethod.GET, "/api/v1/sync/pull", null);
        assertSuccess("pull", pull);
        var data = objectMapper.readTree(body(pull)).path("data").path("entities");
        assertThat(data.path("products").size()).isPositive();
        assertThat(data.path("inventorySessions").size()).as("counting sessions pulled by a Free tenant").isZero();
        assertThat(data.path("stockTransfers").size()).as("transfers pulled by a Free tenant").isZero();
    }

    @Test
    @DisplayName("Free tenant with a counting row ACTIVE (super-admin grant) → counting passes, transfers still refused")
    void freeTenant_rowActive_granted() throws Exception {
        freePlan();
        moduleRow("counting", "ACTIVE");
        assertPassesGate("counting session", call(HttpMethod.POST, "/api/v1/inventory/sessions",
                Map.of("storeId", storeId, "scope", "FULL")));
        assertNotEntitled("transfer", call(HttpMethod.POST, "/api/v1/stock/transfers", Map.of("sourceStoreId",
                storeId, "destinationStoreId", UUID.randomUUID(), "productId", productId, "quantity", 1)));
    }

    @Test
    @DisplayName("Premium trial with an exports row DISABLED → XLSX export 403, CSV export still 200")
    void premium_rowDisabled_refused() throws Exception {
        moduleRow("exports", "DISABLED");
        assertNotEntitled("export xlsx", call(HttpMethod.POST, "/api/v1/exports",
                Map.of("datasets", List.of("stores"), "format", "XLSX")));
        MvcResult csv = call(HttpMethod.POST, "/api/v1/exports", Map.of("datasets", List.of("stores"), "format", "CSV"));
        assertThat(csv.getResponse().getStatus()).as(body(csv)).isEqualTo(200);
    }

    @Test
    @DisplayName("crm DISABLED (free module) → GET clients 403, products still 200")
    void crmDisabled_refused() throws Exception {
        moduleRow("crm", "DISABLED");
        assertNotEntitled("clients", call(HttpMethod.GET, "/api/v1/clients", null));
        assertThat(call(HttpMethod.GET, "/api/v1/products", null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("13.10a review: every export dataset and every sync delta declares its owning module (premium data mapped to its paid module)")
    void dataOwnership() {
        Map<String, String> datasets = new java.util.TreeMap<>();
        exportDatasets.forEach(d -> datasets.put(d.key(), d.moduleId()));
        assertThat(datasets).containsAllEntriesOf(Map.of("inventory_sessions", "counting", "inventory_counts", "counting",
                "stock_transfers", "multi-store", "clients", "crm", "suppliers", "crm", "stores", "store"));
        assertThat(datasets).hasSize(16);
        assertThat(datasets.values()).allSatisfy(m -> assertThat(m).isIn("core", "catalog", "crm", "inventory",
                "counting", "multi-store", "pos", "reporting", "store"));
        Map<String, String> deltas = new java.util.TreeMap<>();
        deltaProviders.forEach(d -> deltas.put(d.entityKey(), d.moduleId()));
        assertThat(deltas).containsAllEntriesOf(Map.of("inventorySessions", "counting", "stockTransfers", "multi-store",
                "products", "catalog", "clients", "crm", "invoices", "invoicing")); // 15.5b: invoices (premium)
        assertThat(deltas).hasSize(16);
    }
}
