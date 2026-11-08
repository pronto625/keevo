package com.keevo.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.11 — {@code GET /api/v1/finance/summary} end to end with the production security filter, JWTs, module gate
 * and permission: the owner's summary of the current month (a POS sale and an expense recorded through the expenses
 * API), period forms, store filter, French 400s, employee refused, module disabled.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Finance summary — REST, gate, permission, period, store scope (Story 20.11)")
class FinanceFullContextTest extends FullContextIntegrationTest {

    private static final String SUMMARY = "/api/v1/finance/summary";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleProvisioner provisioner;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private UUID storeA;
    private UUID storeB;
    private String ownerToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1", UUID.class);
        storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type, is_active, created_at, updated_at) "
                + "VALUES (?, 'Boutique Bonapriso', 'STORE', true, now() + interval '1 second', now())", storeB);
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", employeeId, storeB);
        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema, "OWNER");
        employeeToken = TestAccess.token(jdbc, jwtTokenProvider, employeeId, schema, "EMPLOYEE", "ACTIVE", storeB,
                false);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private MvcResult get(String token, String... params) throws Exception {
        var request = MockMvcRequestBuilders.get(SUMMARY).header("Authorization", "Bearer " + token);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        return objectMapper.readTree(body(r)).path("data");
    }

    private void badRequest(MvcResult r, String message) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(400);
        JsonNode json = objectMapper.readTree(body(r));
        assertThat(json.path("domainCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(json.path("error").asText()).isEqualTo(message);
    }

    @Test
    @DisplayName("owner, current month: sale (20.9 source) − expense (expenses API) = result; Bref shape; stores named")
    void ownerSummary() throws Exception {
        LocalDate first = YearMonth.now(KeevoZones.WAT).atDay(1); // default month: WAT civil date (FUS-NFR9)
        JsonNode empty = ok(get(ownerToken)); // expenses never used: no table yet, zeros
        assertThat(empty.path("expenses").path("total").decimalValue()).isZero();
        assertThat(empty.path("period").path("startDate").asText()).isEqualTo(first.toString());

        jdbc.update("INSERT INTO \"" + schema + "\".sales (id, created_at, employee_id, occurred_at, payment_mode, "
                        + "status, store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, "
                        + "20000, 0)", UUID.randomUUID(), Timestamp.from(first.atTime(12, 0).toInstant(ZoneOffset.UTC)),
                UUID.randomUUID(), Timestamp.from(first.atTime(12, 0).toInstant(ZoneOffset.UTC)), storeA);
        MvcResult created = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/expenses")
                .header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("amount", 5000, "category", "rent",
                        "expenseDate", first.toString(), "storeId", storeB.toString())))).andReturn();
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);

        JsonNode s = ok(get(ownerToken));
        assertThat(s.path("revenue").path("ttc").decimalValue()).isEqualByComparingTo("20000");
        assertThat(s.path("revenue").path("excludingTax").decimalValue()).isEqualByComparingTo("20000");
        assertThat(s.path("revenue").path("paid").decimalValue()).isEqualByComparingTo("20000");
        assertThat(s.path("revenue").path("invoiceCount").asLong()).isEqualTo(1);
        assertThat(s.path("expenses").path("total").decimalValue()).isEqualByComparingTo("5000");
        assertThat(s.path("result").path("net").decimalValue()).isEqualByComparingTo("15000");
        assertThat(s.path("result").path("marginPct").decimalValue()).isEqualByComparingTo("75");
        assertThat(s.path("comparison").path("revenue").path("deltaPct").isNull()).isTrue();
        assertThat(s.path("period").path("granularity").asText()).isEqualTo("day");
        assertThat(s.path("series")).hasSize(first.lengthOfMonth());
        assertThat(s.path("series").get(0).path("result").decimalValue()).isEqualByComparingTo("15000");
        assertThat(s.path("byCategory").get(0).path("categoryLabel").asText()).isEqualTo("Loyer");
        assertThat(s.path("byStore")).hasSize(2);
        assertThat(s.path("byStore").get(1).path("storeName").asText()).isEqualTo("Boutique Bonapriso");

        JsonNode b = ok(get(ownerToken, "storeId", storeB.toString(), "period", first.toString().substring(0, 7)));
        assertThat(b.path("revenue").path("ttc").decimalValue()).isZero();
        assertThat(b.path("result").path("net").decimalValue()).isEqualByComparingTo("-5000");
        assertThat(b.path("byStore")).isEmpty();

        JsonNode year = ok(get(ownerToken, "period", String.valueOf(first.getYear())));
        assertThat(year.path("period").path("granularity").asText()).isEqualTo("month");
        assertThat(year.path("series")).hasSize(12);
    }

    @Test
    @DisplayName("refusals: French 400s for a bad period / date / range / store; employee 403; module disabled 403")
    void refusals() throws Exception {
        badRequest(get(ownerToken, "period", "2026-13"),
                "Période invalide : utilisez AAAA-MM (un mois) ou AAAA (une année).");
        badRequest(get(ownerToken, "period", "2026-09", "startDate", "2026-09-01"),
                "Indiquez soit une période, soit des dates de début et de fin, pas les deux.");
        badRequest(get(ownerToken, "startDate", "2026-02-30"), "Date invalide : utilisez le format AAAA-MM-JJ.");
        badRequest(get(ownerToken, "startDate", "2026-09-10", "endDate", "2026-09-01"),
                "La date de début doit précéder la date de fin.");
        badRequest(get(ownerToken, "startDate", "2025-01-01", "endDate", "2026-06-30"),
                "La période ne peut pas dépasser 366 jours.");
        badRequest(get(ownerToken, "period", "1999"), "L'année doit être comprise entre 2000 et 2100.");
        badRequest(get(ownerToken, "endDate", "2000-01-01"), "La date de début doit précéder la date de fin.");
        MvcResult unknownStore = get(ownerToken, "storeId", UUID.randomUUID().toString());
        assertThat(unknownStore.getResponse().getStatus()).as(body(unknownStore)).isEqualTo(422); // global mapping
        assertThat(objectMapper.readTree(body(unknownStore)).path("domainCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(objectMapper.readTree(body(unknownStore)).path("error").asText()).isEqualTo("Établissement inconnu");

        for (String role : List.of("EMPLOYEE", "MANAGER", "VIEWER")) {
            UUID memberId = role.equals("EMPLOYEE") ? employeeId : UUID.randomUUID();
            if (!role.equals("EMPLOYEE")) {
                jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                        + "password_change_required) VALUES (?, ?, 'Marie', 'Ngo', false)", memberId, storeB);
            }
            MvcResult member = get(TestAccess.token(jdbc, jwtTokenProvider, memberId, schema, role, "ACTIVE", storeB,
                    false));
            assertThat(member.getResponse().getStatus()).as(role + " " + body(member)).isEqualTo(403);
            assertThat(objectMapper.readTree(body(member)).path("domainCode").asText()).isEqualTo("FORBIDDEN");
        }

        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, 'finance', "
                + "'DISABLED', 'FREE') ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = EXCLUDED.status",
                tenantId);
        assertThat(get(ownerToken).getResponse().getStatus()).isEqualTo(403);
    }

    // ── contract summary cases, end to end ──────────────────────────────────────────────────────────────

    private String t(String table) {
        return "\"" + schema + "\"." + table;
    }

    private void sale(UUID storeId, Instant at, int total) {
        jdbc.update("INSERT INTO " + t("sales") + " (id, created_at, employee_id, occurred_at, payment_mode, status, "
                        + "store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, ?, 0)",
                UUID.randomUUID(), Timestamp.from(at), UUID.randomUUID(), Timestamp.from(at), storeId, total);
    }

    private int numbers;

    private void invoice(String status, LocalDate issueDate, long ttc, long vat) {
        Timestamp issuedAt = Timestamp.from(issueDate.atTime(9, 0).toInstant(ZoneOffset.UTC));
        jdbc.update("INSERT INTO " + t("invoices") + " (id, number, status, issue_date, issued_at, paid_at, "
                        + "payment_method, client_name, vat_registered, vat_rate, prices_include_vat, total_ht, total_vat, "
                        + "total_ttc, created_by, kind) VALUES (?, ?, ?, ?, ?, ?, ?, 'Client', ?, 19.25, true, ?, ?, ?, ?, "
                        + "'INVOICE')", UUID.randomUUID(), "FAC-2026-" + String.format("%06d", ++numbers), status,
                java.sql.Date.valueOf(issueDate), issuedAt, "PAID".equals(status) ? issuedAt : null,
                "PAID".equals(status) ? "Espèces" : null, vat > 0, ttc - vat, vat, ttc, ownerId);
    }

    private void expense(UUID storeId, String category, String amount, LocalDate date) {
        jdbc.update("INSERT INTO " + t("expenses") + " (id, store_id, category, amount, expense_date, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), storeId, category, new java.math.BigDecimal(amount),
                date, ownerId);
    }

    @Test
    @DisplayName("AC — the contract's summary cases through GET /api/v1/finance/summary: fixtures that produce each "
            + "case's inputs (20.9 source + expenses), response equal to the contract's expected values")
    void contractSummaryCasesEndToEnd() throws Exception {
        provisioner.ensureProvisioned(schema, "invoicing");
        provisioner.ensureProvisioned(schema, "expenses");
        JsonNode contract = FinanceContract.load(objectMapper);
        Map<String, Runnable> fixtures = Map.of(
                "a month with VAT, two expense categories and growth", () -> {
                    invoice("ISSUED", LocalDate.of(2026, 9, 3), 11925, 1925);              // HT 10 000 on 3 Sept.
                    sale(storeA, Instant.parse("2026-09-10T11:00:00Z"), 5000);              // paid, 10 Sept.
                    invoice("PAID", LocalDate.of(2026, 8, 15), 10000, 0);                   // previous window
                    expense(storeA, "rent", "2000", LocalDate.of(2026, 9, 3));
                    expense(storeB, "salaries", "1000", LocalDate.of(2026, 9, 3));
                },
                "a year without revenue: loss, null margins, cents rounded like JavaScript", () -> {
                    sale(storeA, Instant.parse("2025-06-15T11:00:00Z"), 500);               // previous year
                    for (int i = 0; i < 3; i++) {
                        expense(storeA, "other", "111.11", LocalDate.of(2026, 2, 10 + i));
                    }
                    expense(storeB, "rent", "600", LocalDate.of(2025, 5, 2));
                });
        int run = 0;
        for (JsonNode c : contract.path("summary")) {
            String name = c.path("name").asText();
            assertThat(fixtures).as("fixture for « %s »", name).containsKey(name);
            jdbc.update("DELETE FROM " + t("invoices"));
            jdbc.update("DELETE FROM " + t("sales"));
            jdbc.update("DELETE FROM " + t("expenses"));
            fixtures.get(name).run();
            JsonNode data = ok(get(ownerToken, "startDate", c.path("period").path("startDate").asText(),
                    "endDate", c.path("period").path("endDate").asText()));
            FinanceContract.assertSummary(c.path("expected"), data, name);
            run++;
        }
        assertThat(run).isGreaterThanOrEqualTo(2);
    }
}
