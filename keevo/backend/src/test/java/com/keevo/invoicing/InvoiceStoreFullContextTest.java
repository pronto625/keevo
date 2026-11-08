package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.commerce.api.ClosureWindow;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.reporting.dashboard.domain.model.DashboardSummary;
import com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase;
import com.keevo.reporting.report.application.service.EndOfDayReportBuilder;
import com.keevo.reporting.report.application.service.WeeklyReportBuilder;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.WeeklyReportData;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.module.ModuleRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.14 (FUS-FR24) — the store of a document end to end in the real application context: attribution at
 * creation over REST and sync (store member, owner with / without {@code storeId}, refusals), quote conversion,
 * {@code storeId} / {@code storeName} in responses and in the pull, and the interim AC: an EMPLOYEE of store B issues
 * an invoice → store B's end-of-day report counts it once, the tenant-wide figures count it once.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Store of documents (Story 20.14)")
class InvoiceStoreFullContextTest extends FullContextIntegrationTest {

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry registry;
    @Autowired EndOfDayReportBuilder endOfDay;
    @Autowired GetDashboardSummaryUseCase dashboard;
    @Autowired WeeklyReportBuilder weeklyReport;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private UUID storeA;
    private UUID storeB;
    private String ownerToken;
    private String employeeToken;
    private Instant clientClock = Instant.now().minusSeconds(600);

    @BeforeEach
    void setUp() throws Exception {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1",
                UUID.class);
        storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, now() + interval '1 minute', now(), true, 'Boutique Bonapriso', 'STORE')", storeB);
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", employeeId, storeB);
        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema, "OWNER");
        employeeToken = TestAccess.token(jdbc, jwtTokenProvider, employeeId, schema, "EMPLOYEE", "ACTIVE", storeB,
                false);
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
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        registry.evict(schema);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private static Map<String, Object> content(String clientName, long unitPrice, UUID storeId, String kind) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 1);
        line.put("unitPrice", unitPrice);
        line.put("vatTreatment", "STANDARD");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", Map.of("name", clientName));
        body.put("lines", List.of(line));
        if (storeId != null) {
            body.put("storeId", storeId.toString());
        }
        if (kind != null) {
            body.put("kind", kind);
        }
        return body;
    }

    private JsonNode pushCreate(String token, Map<String, Object> payload) throws Exception {
        clientClock = clientClock.plusSeconds(1);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", "CREATE_INVOICE");
        op.put("entityId", payload.get("id"));
        op.put("payload", payload);
        op.put("clientTimestamp", clientClock.toString());
        JsonNode pushed = data(call(token, HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", UUID.randomUUID().toString(), "operations", List.of(op))), 200);
        return pushed.path("results").get(0);
    }

    private int invoiceCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoices", Integer.class);
    }

    private UUID storeOf(String invoiceId) {
        return jdbc.queryForObject("SELECT store_id FROM \"" + schema + "\".invoices WHERE id = ?::uuid", UUID.class,
                invoiceId);
    }

    private <T> T inTenant(Supplier<T> work) {
        TenantContext.setCurrentTenant(schema);
        try {
            return tx.execute(s -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    private EndOfDayReportData endOfDay(UUID store) {
        LocalDate today = LocalDate.now(WAT);
        ClosureWindow window = new ClosureWindow(today.atStartOfDay(WAT).toInstant(), Instant.now().plusSeconds(60),
                null, null);
        return inTenant(() -> endOfDay.build(store, window, "Boutique", today, LocalTime.of(20, 0), false));
    }

    private DashboardSummary dashboard() {
        return inTenant(() -> dashboard.execute(new GetDashboardSummaryUseCase.GetDashboardSummaryQuery(schema)));
    }

    private static long storeToday(DashboardSummary d, UUID store) {
        return d.storeOverviews().stream().filter(o -> o.storeId().equals(store.toString())).findFirst()
                .orElseThrow().todayCA();
    }

    // ── interim AC ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("an EMPLOYEE of store B issues an invoice: made in B (storeId + name), B's end-of-day report counts it "
            + "once, A's does not, the tenant-wide revenue counts it once")
    void employeeOfStoreB() throws Exception {
        long tenantBefore = dashboard().todayCA();
        EndOfDayReportData bBefore = endOfDay(storeB);

        JsonNode draft = data(call(employeeToken, HttpMethod.POST, "/api/v1/invoices",
                content("Awa Traoré", 10_000, null, null)), 201);
        String id = draft.path("id").asText();
        assertThat(draft.path("storeId").asText()).isEqualTo(storeB.toString());
        assertThat(draft.path("storeName").asText()).isEqualTo("Boutique Bonapriso");
        JsonNode issued = data(call(employeeToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        assertThat(issued.path("storeId").asText()).isEqualTo(storeB.toString());
        assertThat(storeOf(id)).isEqualTo(storeB);

        EndOfDayReportData b = endOfDay(storeB);
        assertThat(b.totalRevenue()).isEqualTo(bBefore.totalRevenue() + 10_000);
        assertThat(b.totalSales()).isEqualTo(bBefore.totalSales() + 1);
        assertThat(endOfDay(storeA).totalRevenue()).isZero();
        DashboardSummary d = dashboard(); // the real bean: ProvisionedRevenueSource, not a test double
        assertThat(d.todayCA()).isEqualTo(tenantBefore + 10_000);
        assertThat(storeToday(d, storeB)).isEqualTo(10_000);
        assertThat(storeToday(d, storeA)).isZero();

        // the list carries the store name too (one lookup for the page)
        JsonNode list = data(call(ownerToken, HttpMethod.GET, "/api/v1/invoices", null), 200);
        assertThat(list).anySatisfy(n -> {
            assertThat(n.path("id").asText()).isEqualTo(id);
            assertThat(n.path("storeName").asText()).isEqualTo("Boutique Bonapriso");
        });
    }

    @Test
    @DisplayName("an EMPLOYEE asking for another store → 403, nothing created")
    void employeeOtherStore() throws Exception {
        MvcResult r = call(employeeToken, HttpMethod.POST, "/api/v1/invoices", content("Awa", 1_000, storeA, null));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoices", Integer.class)).isZero();
    }

    // ── owner ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("owner: the requested store; without it the oldest active store; an unknown or inactive one → 400 "
            + "storeId; an edit never changes the store")
    void owner() throws Exception {
        JsonNode inB = data(call(ownerToken, HttpMethod.POST, "/api/v1/invoices",
                content("Société Ndolo", 5_000, storeB, null)), 201);
        assertThat(inB.path("storeId").asText()).isEqualTo(storeB.toString());

        JsonNode defaulted = data(call(ownerToken, HttpMethod.POST, "/api/v1/invoices",
                content("Société Ndolo", 5_000, null, null)), 201);
        assertThat(defaulted.path("storeId").asText()).isEqualTo(storeA.toString());

        MvcResult unknown = call(ownerToken, HttpMethod.POST, "/api/v1/invoices",
                content("Société Ndolo", 5_000, UUID.randomUUID(), null));
        assertThat(unknown.getResponse().getStatus()).as(body(unknown)).isEqualTo(422);
        JsonNode error = objectMapper.readTree(body(unknown));
        assertThat(error.path("domainCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("error").asText()).isEqualTo("Boutique inconnue ou désactivée");
        jdbc.update("UPDATE \"" + schema + "\".stores SET is_active = false WHERE id = ?", storeB);
        assertThat(call(ownerToken, HttpMethod.POST, "/api/v1/invoices", content("X", 5_000, storeB, null))
                .getResponse().getStatus()).isEqualTo(422);
        jdbc.update("UPDATE \"" + schema + "\".stores SET is_active = true WHERE id = ?", storeB);

        Map<String, Object> edit = content("Société Ndolo SA", 6_000, storeA, null);
        JsonNode edited = data(call(ownerToken, HttpMethod.PUT, "/api/v1/invoices/" + inB.path("id").asText(), edit),
                200);
        assertThat(edited.path("storeId").asText()).isEqualTo(storeB.toString());
    }

    @Test
    @DisplayName("quote of store B converted (REST): the invoice is made in B")
    void conversionCopiesTheStore() throws Exception {
        JsonNode quote = data(call(ownerToken, HttpMethod.POST, "/api/v1/invoices",
                content("Société Ndolo", 11_925, storeB, "QUOTE")), 201);
        assertThat(quote.path("storeId").asText()).isEqualTo(storeB.toString());

        JsonNode invoice = data(call(ownerToken, HttpMethod.POST,
                "/api/v1/invoices/" + quote.path("id").asText() + "/convert", null), 201);
        assertThat(invoice.path("storeId").asText()).isEqualTo(storeB.toString());
        assertThat(invoice.path("storeName").asText()).isEqualTo("Boutique Bonapriso");
        assertThat(storeOf(invoice.path("id").asText())).isEqualTo(storeB);
    }

    @Test
    @DisplayName("sync: CREATE_INVOICE of an EMPLOYEE of B is made in B; the pull carries storeId and storeName")
    void sync() throws Exception {
        String id = UUID.randomUUID().toString();
        Map<String, Object> payload = content("Awa", 2_000, null, null);
        payload.put("id", id);
        clientClock = clientClock.plusSeconds(1);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", "CREATE_INVOICE");
        op.put("entityId", id);
        op.put("payload", payload);
        op.put("clientTimestamp", clientClock.toString());
        JsonNode pushed = data(call(employeeToken, HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", UUID.randomUUID().toString(), "operations", List.of(op))), 200);
        assertThat(pushed.path("results").get(0).path("status").asText()).as(pushed.toString()).isEqualTo("APPLIED");
        assertThat(storeOf(id)).isEqualTo(storeB);

        JsonNode pulled = data(mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/sync/pull")
                .header("Authorization", "Bearer " + ownerToken)).andReturn(), 200);
        assertThat(pulled.path("entities").path("invoices")).anySatisfy(n -> {
            assertThat(n.path("id").asText()).isEqualTo(id);
            assertThat(n.path("storeId").asText()).isEqualTo(storeB.toString());
            assertThat(n.path("storeName").asText()).isEqualTo("Boutique Bonapriso");
        });
    }

    // ── review 1 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sync refusals: an EMPLOYEE of B asking for A → REJECTED FORBIDDEN; an owner's unknown or inactive "
            + "store → REJECTED VALIDATION_ERROR; the French message in conflictData, nothing created")
    void syncRefusals() throws Exception {
        Map<String, Object> other = content("Awa", 2_000, storeA, null);
        other.put("id", UUID.randomUUID().toString());
        JsonNode r1 = pushCreate(employeeToken, other);
        assertThat(r1.path("status").asText()).as(r1.toString()).isEqualTo("REJECTED");
        assertThat(r1.path("reason").asText()).isEqualTo("FORBIDDEN");
        assertThat(r1.path("conflictData").path("message").asText())
                .isEqualTo("Vous ne pouvez créer un document que dans votre boutique");

        for (UUID bad : List.of(UUID.randomUUID(), storeB)) {
            if (bad.equals(storeB)) {
                jdbc.update("UPDATE \"" + schema + "\".stores SET is_active = false WHERE id = ?", storeB);
            }
            Map<String, Object> unknown = content("Awa", 2_000, bad, null);
            unknown.put("id", UUID.randomUUID().toString());
            JsonNode r2 = pushCreate(ownerToken, unknown);
            assertThat(r2.path("status").asText()).as(r2.toString()).isEqualTo("REJECTED");
            assertThat(r2.path("reason").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(r2.path("conflictData").path("message").asText()).isEqualTo("Boutique inconnue ou désactivée");
        }
        assertThat(invoiceCount()).isZero();
    }

    @Test
    @DisplayName("an EMPLOYEE whose store is deactivated → 403 with an actionable French message")
    void employeeOfAnInactiveStore() throws Exception {
        jdbc.update("UPDATE \"" + schema + "\".stores SET is_active = false WHERE id = ?", storeB);
        MvcResult r = call(employeeToken, HttpMethod.POST, "/api/v1/invoices", content("Awa", 1_000, null, null));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(403);
        assertThat(objectMapper.readTree(body(r)).path("error").asText()).startsWith("Votre boutique est désactivée");
        assertThat(invoiceCount()).isZero();
    }

    @Test
    @DisplayName("quote of B converted after B was deactivated → the invoice is made in the default store")
    void conversionOfAQuoteOfAnInactiveStore() throws Exception {
        JsonNode quote = data(call(ownerToken, HttpMethod.POST, "/api/v1/invoices",
                content("Société Ndolo", 11_925, storeB, "QUOTE")), 201);
        jdbc.update("UPDATE \"" + schema + "\".stores SET is_active = false WHERE id = ?", storeB);
        JsonNode invoice = data(call(ownerToken, HttpMethod.POST,
                "/api/v1/invoices/" + quote.path("id").asText() + "/convert", null), 201);
        assertThat(invoice.path("storeId").asText()).isEqualTo(storeA.toString());
    }

    @Test
    @DisplayName("through the real revenue source: an employee who only issued invoices is listed for its "
            + "end-of-day report and gets its figures; the weekly report of B counts the invoice")
    void employeeOnlyInvoicesAndWeekly() throws Exception {
        JsonNode draft = data(call(employeeToken, HttpMethod.POST, "/api/v1/invoices",
                content("Awa Traoré", 8_000, null, null)), 201);
        data(call(employeeToken, HttpMethod.POST, "/api/v1/invoices/" + draft.path("id").asText() + "/issue", null),
                200);
        LocalDate today = LocalDate.now(WAT);
        ClosureWindow window = new ClosureWindow(today.atStartOfDay(WAT).toInstant(), Instant.now().plusSeconds(60),
                null, null);

        List<UUID> employees = inTenant(() -> endOfDay.getDistinctEmployeeIds(storeB, window));
        assertThat(employees).containsExactly(employeeId);
        EndOfDayReportData mine = inTenant(() -> endOfDay.buildForEmployee(storeB, employeeId, window, "Boutique",
                today, LocalTime.of(20, 0), false, "Paul"));
        assertThat(mine.totalRevenue()).isEqualTo(8_000);
        assertThat(mine.totalSales()).isEqualTo(1);

        ClosureWindow week = new ClosureWindow(today.minusDays(6).atStartOfDay(WAT).toInstant(),
                Instant.now().plusSeconds(60), null, null);
        WeeklyReportData weekly = inTenant(() -> weeklyReport.build(storeB, week, "Boutique", today, schema, false));
        assertThat(weekly.totalRevenue()).isEqualTo(8_000);
        assertThat(weekly.totalSales()).isEqualTo(1);
        assertThat(inTenant(() -> weeklyReport.build(storeA, week, "Boutique", today, schema, false))
                .totalRevenue()).isZero();
    }
}
