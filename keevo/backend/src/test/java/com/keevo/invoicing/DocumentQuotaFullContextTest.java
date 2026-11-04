package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase.CreateProductDto;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanQuota;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.PlanProfileAdmin;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.application.port.PlanCatalogStore;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Story 19.8 — the documents quota end to end in the real application context: a user's issue (REST and sync
 * {@code ISSUE_INVOICE}) counts one document; over quota it is refused with {@code QUOTA_EXCEEDED} and its details
 * (403 / REJECTED with {@code conflictData}) and the invoice stays a draft; a replayed operation is not counted twice;
 * a POS sale and its automatic invoice go through with the documents used up; {@code GET /subscription/me} gains
 * {@code quotas} and {@code credits} (V1 fields unchanged); the super-admin edits a plan's quotas (Keep / Replace, 422);
 * a manual credit note is not counted.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Documents quota — invoice issue, sync, POS bridge, Mon plan, admin (Story 19.8)")
class DocumentQuotaFullContextTest extends FullContextIntegrationTest {

    private static final String PLAN = "TEST_QUOTA_FC";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanModuleRowSync rowSync;
    @Autowired PlanCatalogStore catalogStore;
    @Autowired ModuleRegistry registry;
    @Autowired CreateProductUseCase createProduct;
    @Autowired UsageQuotaPort quotas;
    @Autowired PlanProfileAdmin profileAdmin;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private UUID storeId;
    private String ownerToken;
    private String adminToken;
    private final String month = YearMonth.now(ZoneId.of("Africa/Douala")).toString();
    private Instant clientClock = Instant.now().minusSeconds(600);
    private final String deviceId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        ownerToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema,
                "OWNER");
        adminToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, adminId,
                "public", "SUPER_ADMIN");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("legalName", "Boutique Awa SARL");
        settings.put("address", "Rue Joss, Akwa, Douala");
        settings.put("vatRegistered", false);
        settings.put("vatRate", 19.25);
        settings.put("pricesIncludeVat", true);
        data(call(ownerToken, HttpMethod.PUT, "/api/v1/invoicing/settings", settings), 200);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.usage_counters WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM public.usage_credits WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenantId);
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM public.plan_quotas WHERE plan_type = ?", PLAN);
        jdbc.update("DELETE FROM public.profile_modules WHERE plan_type = ?", PLAN);
        jdbc.update("DELETE FROM public.plan_profiles WHERE plan_type = ?", PLAN);
        jdbc.update("DELETE FROM public.plans WHERE code = ?", PLAN);
        catalogStore.invalidate();
        registry.evict(schema);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The tenant moves to a paid test plan (every paid module, no limit) whose documents quota is {@code limit}. */
    private void testPlan(Integer documentsPerMonth) {
        jdbc.update("INSERT INTO public.plans (code, label, sort_order, visible, kind) VALUES (?, 'Offre test', 98, "
                + "false, 'PAID') ON CONFLICT (code) DO NOTHING", PLAN);
        jdbc.update("INSERT INTO public.plan_profiles (plan_type) VALUES (?) ON CONFLICT (plan_type) DO NOTHING",
                PLAN);
        if (documentsPerMonth != null) {
            jdbc.update("INSERT INTO public.plan_quotas (plan_type, metric, period, quota_limit) VALUES (?, "
                    + "'DOCUMENTS', 'MONTH', ?)", PLAN, documentsPerMonth);
        }
        catalogStore.invalidate();
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = ?, status = 'ACTIVE', "
                + "expires_at = now() + interval '30 days'", PLAN);
        jdbc.update("UPDATE public.tenants SET plan_type = ? WHERE id = ?", PLAN, tenantId);
        rowSync.sync(tenantId, schema);
        registry.evict(schema);
    }

    private long documents() {
        List<Long> rows = jdbc.queryForList("SELECT used FROM public.usage_counters WHERE tenant_id = ? "
                + "AND metric = 'DOCUMENTS' AND period_key = ?", Long.class, tenantId, month);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode data(MvcResult r, int expectedStatus) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(expectedStatus);
        return objectMapper.readTree(body(r)).path("data");
    }

    private static Map<String, Object> draftBody() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 1);
        line.put("unitPrice", 11_925);
        line.put("vatTreatment", "STANDARD");
        return Map.of("client", Map.of("name", "Société Ndolo"), "lines", List.of(line));
    }

    private String draft() throws Exception {
        return data(call(ownerToken, HttpMethod.POST, "/api/v1/invoices", draftBody()), 201).path("id").asText();
    }

    private MvcResult issue(String id) throws Exception {
        return call(ownerToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null);
    }

    private Map<String, Object> invoice(String id) {
        return jdbc.queryForMap("SELECT status, number FROM \"" + schema + "\".invoices WHERE id = ?::uuid", id);
    }

    private Map<String, Object> op(String type, String invoiceId, Map<String, Object> payload) {
        clientClock = clientClock.plusSeconds(1);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        op.put("entityId", invoiceId);
        op.put("payload", payload);
        op.put("clientTimestamp", clientClock.toString());
        return op;
    }

    private Map<String, Object> createOp(String invoiceId) {
        Map<String, Object> p = new LinkedHashMap<>(draftBody());
        p.put("id", invoiceId);
        return op("CREATE_INVOICE", invoiceId, p);
    }

    private Map<String, Object> issueOp(String invoiceId) {
        return op("ISSUE_INVOICE", invoiceId, Map.of("invoiceId", invoiceId));
    }

    @SafeVarargs
    private Map<String, JsonNode> push(Map<String, Object>... ops) throws Exception {
        JsonNode data = data(call(ownerToken, HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", deviceId, "operations", List.of(ops))), 200);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode result : data.path("results")) {
            byId.put(result.path("operationId").asText(), result);
        }
        return byId;
    }

    private static JsonNode result(Map<String, JsonNode> results, Map<String, Object> op) {
        return results.get((String) op.get("operationId"));
    }

    // ── REST issue ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("REST issue counts 1 document (trial: unlimited, still counted); a draft does not count")
    void issueCountsOne() throws Exception {
        String id = draft();
        assertThat(documents()).isZero();
        data(issue(id), 200);
        assertThat(documents()).isEqualTo(1);
    }

    @Test
    @DisplayName("REST issue over quota → 403 QUOTA_EXCEEDED with French message and details; the invoice stays a "
            + "draft without number")
    void issueOverQuota() throws Exception {
        testPlan(1);
        data(issue(draft()), 200);
        String second = draft();

        MvcResult r = issue(second);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(403);
        JsonNode json = objectMapper.readTree(body(r));
        assertThat(json.path("domainCode").asText()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(json.path("error").asText()).isEqualTo("Quota de votre offre atteint");
        JsonNode details = json.path("details");
        assertThat(details.path("metric").asText()).isEqualTo("DOCUMENTS");
        assertThat(details.path("metricLabel").asText()).isEqualTo("Documents");
        assertThat(details.path("period").asText()).isEqualTo("MONTH");
        assertThat(details.path("periodLabel").asText()).isEqualTo("par mois");
        assertThat(details.path("limit").asInt()).isEqualTo(1);
        assertThat(details.path("used").asLong()).isEqualTo(1);
        assertThat(details.path("planCode").asText()).isEqualTo(PLAN);
        assertThat(details.path("planLabel").asText()).isEqualTo("Offre test");
        LocalDate firstOfNext = YearMonth.parse(month).plusMonths(1).atDay(1);
        assertThat(Instant.parse(details.path("resetAt").asText()))
                .isEqualTo(firstOfNext.atStartOfDay(ZoneId.of("Africa/Douala")).toInstant());

        Map<String, Object> row = invoice(second);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("number")).isNull();
        assertThat(documents()).isEqualTo(1);
    }

    // ── sync ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sync ISSUE_INVOICE: counted once; the same operation replayed is DUPLICATE and not counted again")
    void syncIssueReplayNotCountedTwice() throws Exception {
        String id = UUID.randomUUID().toString();
        var create = createOp(id);
        var issue = issueOp(id);
        Map<String, JsonNode> first = push(create, issue);
        assertThat(result(first, issue).path("status").asText()).as(first.toString()).isEqualTo("APPLIED");
        assertThat(documents()).isEqualTo(1);

        Map<String, JsonNode> replay = push(issue);
        assertThat(result(replay, issue).path("status").asText()).isEqualTo("DUPLICATE");
        assertThat(documents()).isEqualTo(1);
    }

    @Test
    @DisplayName("sync ISSUE_INVOICE over quota → REJECTED QUOTA_EXCEEDED with conflictData; the invoice stays a draft")
    void syncIssueOverQuota() throws Exception {
        testPlan(0);
        String id = UUID.randomUUID().toString();
        var create = createOp(id);
        var issue = issueOp(id);
        Map<String, JsonNode> results = push(create, issue);

        assertThat(result(results, create).path("status").asText()).isEqualTo("APPLIED");
        JsonNode refused = result(results, issue);
        assertThat(refused.path("status").asText()).isEqualTo("REJECTED");
        assertThat(refused.path("reason").asText()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(refused.path("conflictData").path("metric").asText()).isEqualTo("DOCUMENTS");
        assertThat(refused.path("conflictData").path("limit").asInt()).isZero();
        assertThat(refused.path("conflictData").path("resetAt").asText()).isNotBlank();
        assertThat(invoice(id).get("status")).isEqualTo("DRAFT");
        assertThat(documents()).isZero();
    }

    // ── POS bridge ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("documents used up: a POS sale completes and its automatic invoice is created (never counted)")
    void posSaleNotBlocked() throws Exception {
        testPlan(0);
        TenantContext.setCurrentTenant(schema);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                ownerId, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        UUID productId;
        try {
            productId = createProduct.execute(new CreateProductDto("Savon 19.8", null, null, null, 1000, null,
                    null, 0, ownerId, 0, null, "OWNER", null, null, null)).getId();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
        if (jdbc.update("UPDATE \"" + schema + "\".stock_levels SET quantity = 100 WHERE product_id = ? "
                + "AND store_id = ?", productId, storeId) == 0) {
            jdbc.update("INSERT INTO \"" + schema + "\".stock_levels (product_id, store_id, quantity) "
                    + "VALUES (?, ?, 100)", productId, storeId);
        }
        UUID saleId = UUID.randomUUID();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("productName", "Savon 19.8");
        item.put("catalogueUnitPrice", 1000);
        item.put("appliedUnitPrice", 1000);
        item.put("quantity", 1);
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("saleId", saleId);
        sale.put("paymentMode", "CASH");
        sale.put("storeId", storeId);
        sale.put("discountAmount", 0);
        sale.put("items", List.of(item));
        MvcResult r = call(ownerToken, HttpMethod.POST, "/api/v1/sales", sale);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);

        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList("SELECT status FROM \"" + schema
                + "\".invoices WHERE source_sale_id = ?", String.class, saleId)).containsExactly("PAID"));
        assertThat(documents()).isZero();
    }

    // ── manual credit note ───────────────────────────────────────────────────

    @Test
    @DisplayName("a manual credit note is not counted as a document")
    void manualCreditNoteNotCounted() throws Exception {
        String id = draft();
        data(issue(id), 200);
        assertThat(documents()).isEqualTo(1);
        data(call(ownerToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/credit-notes",
                Map.of("reason", "Erreur de facturation")), 201);
        assertThat(documents()).isEqualTo(1);
    }

    // ── port contract ────────────────────────────────────────────────────────

    @Test
    @DisplayName("an increment needs the caller's transaction (MANDATORY)")
    void mandatoryTransaction() {
        assertThatThrownBy(() -> quotas.assertAndIncrement(tenantId, UsageMetric.SMS, 1))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ── /subscription/me ─────────────────────────────────────────────────────

    @Test
    @DisplayName("/subscription/me: V1 fields unchanged, quotas (trial = BUSINESS grid) and credits added")
    void subscriptionMe() throws Exception {
        data(issue(draft()), 200);
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO public.usage_credits (tenant_id, metric, remaining) "
                + "VALUES (?, 'SMS', 12)", tenantId));

        JsonNode me = data(call(ownerToken, HttpMethod.GET, "/api/v1/subscription/me", null), 200);
        assertThat(me.path("planType").asText()).isEqualTo("PREMIUM_TRIAL");
        assertThat(me.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(me.has("currentStores")).isTrue();
        assertThat(me.has("currentEmployees")).isTrue();
        List<String> rows = new ArrayList<>();
        for (JsonNode q : me.path("quotas")) {
            rows.add(q.path("metric").asText() + "/" + q.path("period").asText() + "/"
                    + (q.path("limit").isNull() ? "-" : q.path("limit").asText()) + "/" + q.path("used").asText()
                    + "/" + (q.path("remaining").isNull() ? "-" : q.path("remaining").asText()));
        }
        assertThat(rows).containsExactly("DOCUMENTS/MONTH/-/1/-", "SMS/MONTH/80/0/80", "EMAILS/MONTH/-/0/-",
                "AI_CREDITS/MONTH/-/0/-", "AI_CREDITS/DAY/50/0/50");
        assertThat(me.path("quotas").get(0).path("resetAt").asText()).isNotBlank();
        assertThat(me.path("quotas").get(4).path("metricLabel").asText()).isEqualTo("Crédits IA");
        assertThat(me.path("quotas").get(4).path("periodLabel").asText()).isEqualTo("par jour");
        assertThat(me.path("credits").path("SMS").asInt()).isEqualTo(12);
    }

    // ── admin ────────────────────────────────────────────────────────────────

    private Map<String, Object> profileBody() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("maxStores", null);
        b.put("maxProducts", null);
        b.put("maxEmployees", null);
        b.put("modules", List.of("advanced-reports", "alerts", "counting", "exports", "invoicing", "multi-store",
                "profitability", "report-config"));
        return b;
    }

    private JsonNode adminProfile() throws Exception {
        return data(call(adminToken, HttpMethod.GET, "/api/v1/admin/plan-profiles/" + PLAN, null), 200);
    }

    private static List<String> quotaRows(JsonNode profile) {
        List<String> rows = new ArrayList<>();
        profile.path("quotas").forEach(q -> rows.add(q.path("metric").asText() + "/" + q.path("period").asText()
                + "=" + q.path("limit").asInt()));
        return rows;
    }

    @Test
    @DisplayName("admin: GET shows quotas; PUT replaces them (next increment), absent keeps them, invalid list → 422")
    void adminQuotas() throws Exception {
        testPlan(300);
        assertThat(quotaRows(adminProfile())).containsExactly("DOCUMENTS/MONTH=300");
        JsonNode list = data(call(adminToken, HttpMethod.GET, "/api/v1/admin/plan-profiles", null), 200);
        for (JsonNode p : list) {
            if (p.path("planType").asText().equals("FREE")) {
                assertThat(quotaRows(p)).contains("DOCUMENTS/MONTH=300", "SMS/LIFETIME=5");
            }
        }

        Map<String, Object> replace = profileBody();
        replace.put("quotas", List.of(Map.of("metric", "DOCUMENTS", "period", "MONTH", "limit", 1),
                Map.of("metric", "AI_CREDITS", "period", "DAY", "limit", 0)));
        JsonNode saved = data(call(adminToken, HttpMethod.PUT, "/api/v1/admin/plan-profiles/" + PLAN, replace), 200);
        assertThat(quotaRows(saved.path("profile"))).containsExactlyInAnyOrder("DOCUMENTS/MONTH=1",
                "AI_CREDITS/DAY=0");

        data(call(adminToken, HttpMethod.PUT, "/api/v1/admin/plan-profiles/" + PLAN, profileBody()), 200);
        assertThat(quotaRows(adminProfile())).as("absent = kept").containsExactlyInAnyOrder("DOCUMENTS/MONTH=1",
                "AI_CREDITS/DAY=0");

        // applies to the next increment
        data(issue(draft()), 200);
        assertThat(issue(draft()).getResponse().getStatus()).isEqualTo(403);

        for (Object bad : List.of(
                List.of(Map.of("metric", "PAGES", "period", "MONTH", "limit", 1)),
                List.of(Map.of("metric", "SMS", "period", "WEEK", "limit", 1)),
                List.of(Map.of("metric", "SMS", "period", "MONTH", "limit", -1)),
                List.of(Map.of("metric", "SMS", "period", "MONTH", "limit", 1),
                        Map.of("metric", "SMS", "period", "MONTH", "limit", 2)),
                List.of(Map.of("metric", "SMS", "period", "MONTH", "limit", 1, "foo", 1)),
                List.of(Map.of("metric", "SMS", "period", "MONTH")),
                List.of(Map.of("metric", "SMS", "period", "MONTH", "limit", 1.5)),
                java.util.Arrays.asList((Object) null),
                "beaucoup")) {
            Map<String, Object> invalid = profileBody();
            invalid.put("quotas", bad);
            MvcResult r = call(adminToken, HttpMethod.PUT, "/api/v1/admin/plan-profiles/" + PLAN, invalid);
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(422);
            assertThat(body(r)).contains("PLAN_PROFILE_INVALID");
        }
        assertThat(quotaRows(adminProfile())).as("nothing saved").containsExactlyInAnyOrder("DOCUMENTS/MONTH=1",
                "AI_CREDITS/DAY=0");
        // a caller other than the controller: a null element is a 422 too, not a 500
        DomainException nullQuota = org.assertj.core.api.Assertions.catchThrowableOfType(DomainException.class,
                () -> profileAdmin.update(PlanType.of(PLAN), null, null, null, PlanProfileAdmin.LimitPatch.KEEP,
                        java.util.Set.of(), new PlanProfileAdmin.QuotaPatch.Replace(java.util.Arrays.asList(
                                (PlanQuota) null)), adminId));
        assertThat(nullQuota.getErrorCode()).isEqualTo(ErrorCode.PLAN_PROFILE_INVALID);
        assertThat(adminProfile().path("quotas").get(0).path("metricLabel").asText()).isNotBlank();
        assertThat(adminProfile().path("quotas").get(0).path("periodLabel").asText()).isNotBlank();

        Map<String, Object> clear = profileBody();
        clear.put("quotas", List.of());
        data(call(adminToken, HttpMethod.PUT, "/api/v1/admin/plan-profiles/" + PLAN, clear), 200);
        assertThat(quotaRows(adminProfile())).isEmpty();
    }
}
