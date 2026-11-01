package com.keevo.admin.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.job.AdminJobs;
import com.keevo.shared.job.application.AdminJobRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Story 16.4 — the « Modules » console API in the real application context on PostgreSQL with the shipped catalogue:
 * the backfill as a background job (202, progress, report; a second start → 409), the boot-time marking of
 * interrupted jobs, a plan-profile change refused while its re-sync slot is taken (nothing saved), the cascade
 * deactivation (409 listing the dependents, then cascade with force through the real transaction manager) and the
 * tenant's module history (pack applied, deactivations; French labels, no identifier).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("« Modules » console — full context (Story 16.4)")
class ModuleConsoleFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminJobs jobs;
    @Autowired AdminJobRunner runner;

    private final UUID adminId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        awaitJobs();
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        adminToken = jwtTokenProvider.generateAccessToken(adminId, "public", "SUPER_ADMIN");
    }

    @AfterEach
    void tearDown() {
        awaitJobs();
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId); // test tenant only
        TenantContext.clear();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void awaitJobs() {
        long deadline = System.currentTimeMillis() + 120_000;
        while (jdbc.queryForObject("SELECT count(*) FROM public.admin_jobs WHERE status IN ('QUEUED', 'RUNNING')",
                Integer.class) > 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("admin job still running after 120 s");
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private MvcResult admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + adminToken)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(body(r)).get("data");
    }

    /** Polls the job until it is finished; returns its last answer. */
    private JsonNode awaitJob(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        while (true) {
            JsonNode job = data(admin(get("/api/v1/admin/jobs/{id}", jobId)));
            String status = job.get("status").asText();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                return job;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("job " + jobId + " still " + status);
            }
            sleep();
        }
    }

    private String status(String moduleId) {
        List<String> rows = jdbc.queryForList("SELECT status FROM public.tenant_modules WHERE tenant_id = ? "
                + "AND module_id = ?", String.class, tenantId, moduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── jobs ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("backfill dry run: 202 {jobId} at once; the job ends SUCCEEDED with progress = total and the "
            + "backfill report (tenants by code); a second start while one is queued → 409 in French")
    void backfillJob() throws Exception {
        MvcResult started = admin(post("/api/v1/admin/modules/backfill").param("dryRun", "true"));
        assertThat(started.getResponse().getStatus()).as(body(started)).isEqualTo(202);
        String jobId = data(started).get("jobId").asText();

        JsonNode job = awaitJob(jobId);
        assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("SUCCEEDED");
        assertThat(job.get("label").asText()).isEqualTo("Migration des modules");
        assertThat(job.get("params").get("dryRun").asBoolean()).isTrue();
        assertThat(job.get("total").asInt()).isPositive();
        assertThat(job.get("processed").asInt()).isEqualTo(job.get("total").asInt());
        assertThat(job.get("report").get("dryRun").asBoolean()).isTrue();
        assertThat(job.get("report").has("tenantsProcessed")).isTrue();
        assertThat(job.get("report").toString()).doesNotContain(schema);

        AdminJobs.Reservation held = jobs.reserve(AdminJobs.Kind.MODULE_BACKFILL, Map.of(), adminId);
        try {
            MvcResult refused = admin(post("/api/v1/admin/modules/backfill"));
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused)).contains("ADMIN_JOB_RUNNING")
                    .contains("« Migration des modules » est déjà en cours");
        } finally {
            held.cancel();
        }
        MvcResult list = admin(get("/api/v1/admin/jobs").param("kind", "MODULE_BACKFILL"));
        assertThat(data(list).get(0).get("id").asText()).isEqualTo(jobId);
    }

    @Test
    @DisplayName("restart: jobs left queued or running are marked FAILED « Interrompu par un redémarrage »")
    void restartMarksInterrupted() throws Exception {
        UUID running = UUID.randomUUID();
        jdbc.update("INSERT INTO public.admin_jobs (id, kind, status, started_at, created_at) VALUES (?, "
                + "'MODULE_PROVISION_ALL', 'RUNNING', now() - interval '1 hour', now() - interval '1 hour')", running);
        UUID recent = UUID.randomUUID(); // started by this instance since its start: left alone
        jdbc.update("INSERT INTO public.admin_jobs (id, kind, status, started_at) VALUES (?, 'MODULE_BACKFILL', "
                + "'RUNNING', now())", recent);
        // an unknown kind (e.g. written by a newer version before a rollback): skipped in lists, 404 by id
        UUID unknown = UUID.randomUUID();
        jdbc.execute("ALTER TABLE public.admin_jobs DROP CONSTRAINT admin_jobs_kind_check");
        try {
            jdbc.update("INSERT INTO public.admin_jobs (id, kind, status) VALUES (?, 'FUTURE_KIND', 'SUCCEEDED')",
                    unknown);
            MvcResult list = admin(get("/api/v1/admin/jobs"));
            assertThat(list.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(list)).doesNotContain(unknown.toString());
            assertThat(admin(get("/api/v1/admin/jobs/{id}", unknown)).getResponse().getStatus()).isEqualTo(404);
        } finally {
            jdbc.update("DELETE FROM public.admin_jobs WHERE id = ?", unknown);
            jdbc.execute("ALTER TABLE public.admin_jobs ADD CONSTRAINT admin_jobs_kind_check CHECK (kind IN "
                    + "('MODULE_BACKFILL', 'MODULE_PROVISION_ALL', 'PROFILE_RESYNC'))");
        }

        runner.failInterruptedJobs();

        assertThat(data(admin(get("/api/v1/admin/jobs/{id}", recent))).get("status").asText()).isEqualTo("RUNNING");
        jdbc.update("UPDATE public.admin_jobs SET status = 'FAILED' WHERE id = ?", recent); // free the kind

        JsonNode job = data(admin(get("/api/v1/admin/jobs/{id}", running)));
        assertThat(job.get("status").asText()).isEqualTo("FAILED");
        assertThat(job.get("error").asText()).isEqualTo("Interrompu par un redémarrage");
        assertThat(job.get("finishedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("profile composition change while a re-sync is running → 409, profile not saved")
    void profileChangeRefusedWhileResyncRuns() throws Exception {
        String before = jdbc.queryForObject("SELECT string_agg(module_id || ':' || granted, ',' ORDER BY module_id) "
                + "FROM public.profile_modules WHERE plan_type = 'FREE'", String.class);
        AdminJobs.Reservation held = jobs.reserve(AdminJobs.Kind.PROFILE_RESYNC, Map.of(), adminId);
        try {
            MvcResult refused = admin(put("/api/v1/admin/plan-profiles/{planType}", "FREE")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"maxStores\":1,\"maxProducts\":500,\"maxEmployees\":2,\"modules\":[\"exports\"]}"));
            assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(409);
            assertThat(body(refused)).contains("ADMIN_JOB_RUNNING");
        } finally {
            held.cancel();
        }
        assertThat(jdbc.queryForObject("SELECT string_agg(module_id || ':' || granted, ',' ORDER BY module_id) "
                + "FROM public.profile_modules WHERE plan_type = 'FREE'", String.class)).isEqualTo(before);
    }

    // ── cascade, force, history ──────────────────────────────────────────────

    @Test
    @DisplayName("cascade: deactivating « Rapports » on a trial tenant → 409 listing its paid dependents by name; "
            + "with cascade (and force) every dependent then Rapports DISABLED; the history lists the deactivations "
            + "and a pack applied, newest first, French labels, no identifier")
    void cascadeAndHistory() throws Exception {
        MvcResult refused = admin(post("/api/v1/admin/tenants/{t}/modules/{m}/deactivate", tenantId, "reporting"));
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(409);
        assertThat(body(refused)).contains("MODULE_HAS_DEPENDENTS").contains("Rentabilité")
                .contains("Exports PDF et Excel").contains("Rapports avancés").contains("Configuration des rapports");
        assertThat(status("reporting")).isNotEqualTo("DISABLED");

        MvcResult cascaded = admin(post("/api/v1/admin/tenants/{t}/modules/{m}/deactivate", tenantId, "reporting")
                .param("cascade", "true").param("force", "true"));
        assertThat(cascaded.getResponse().getStatus()).as(body(cascaded)).isEqualTo(200);
        JsonNode result = data(cascaded);
        assertThat(result.get("deactivated")).hasSize(5);
        assertThat(result.get("deactivated").get(4).get("displayName").asText()).isEqualTo("Rapports");
        assertThat(result.get("forced")).isEmpty();
        for (String id : List.of("reporting", "exports", "profitability", "advanced-reports", "report-config")) {
            assertThat(status(id)).as(id).isEqualTo("DISABLED");
        }

        // a pack applied with an end date: Facturation (disabled first) activated until that date
        admin(post("/api/v1/admin/tenants/{t}/modules/{m}/deactivate", tenantId, "invoicing"));
        String end = LocalDate.now().plusMonths(6).toString();
        MvcResult applied = admin(post("/api/v1/admin/tenants/{t}/bundles/{b}", tenantId, "pro")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\",\"expiresAt\":\"" + end
                        + "\"}"));
        assertThat(applied.getResponse().getStatus()).as(body(applied)).isEqualTo(200);
        assertThat(status("invoicing")).isEqualTo("ACTIVE");
        JsonNode modules = data(admin(get("/api/v1/admin/tenants/{t}/modules", tenantId)));
        for (JsonNode m : modules) {
            if (m.get("moduleId").asText().equals("invoicing")) {
                assertThat(m.get("origin").asText()).isEqualTo("ADMIN");
                assertThat(m.get("state").asText()).isEqualTo("ENABLED");
            }
        }

        MvcResult history = admin(get("/api/v1/admin/tenants/{t}/modules/history", tenantId));
        assertThat(history.getResponse().getStatus()).as(body(history)).isEqualTo(200);
        JsonNode entries = data(history);
        List<String> actions = new java.util.ArrayList<>();
        entries.forEach(e -> actions.add(e.get("action").asText()));
        assertThat(actions.get(0)).isEqualTo("Pack appliqué");
        assertThat(actions).contains("Module activé", "Module désactivé");
        assertThat(entries.get(0).get("subject").asText()).isEqualTo("Pack Pro");
        assertThat(entries.get(0).get("detail").asText()).contains("Facturation").contains("Actif jusqu'au");
        assertThat(entries.get(0).get("actorName").asText()).isEqualTo("Administrateur Keevo");
        String text = body(history);
        assertThat(text).doesNotContain(tenantId.toString()).doesNotContain(adminId.toString())
                .doesNotContain(schema).doesNotContain("MODULE_");
    }
}
