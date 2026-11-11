package com.keevo.whatsappcloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.whatsappcloud.campaign.application.CampaignDispatcher;
import com.keevo.whatsappcloud.graph.adapter.out.FakeMetaGraphClient;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;
import com.keevo.whatsappcloud.webhook.application.MetaWebhookSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 21.6 — the company's WhatsApp campaigns end to end on PostgreSQL with the production beans and the fake Graph
 * API: audience (consent, STOP, one per phone, stores), asynchronous batches from the company number, consent
 * re-checked at send time, Meta refusals / timeouts / rate limit, statuses → counters, one SENDING per company,
 * scheduled / cancelled, isolation, rights; STOP / START on the company number and on the platform number (with the
 * once-a-minute confirmation); media-header template sends; the platform-number inbox for the super-admin.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("WhatsApp campaigns, STOP / START, media headers, platform inbox on the full context (21.6)")
class WhatsAppCampaignFullContextTest extends FullContextIntegrationTest {

    static final String SECRET = "test-whatsapp-app-secret";
    static final String PLATFORM_NUMBER = "100000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry moduleRegistry;
    @Autowired MetaGraphClient graph;
    @Autowired CampaignDispatcher dispatcher;
    @Autowired com.keevo.shared.module.ModuleProvisioner provisioner;

    record Member(UUID user, String schema, String jwt) {
    }

    private final List<String> schemas = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();
    private FakeMetaGraphClient fake;
    private String schema;
    private Member owner;
    private UUID store;
    private String number;

    @BeforeEach
    void setUp() throws Exception {
        fake = (FakeMetaGraphClient) AopTestUtils.getUltimateTargetObject(graph);
        schema = newTenant();
        owner = owner(schema);
        store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1", UUID.class);
        number = connect(owner);
    }

    @AfterEach
    void tearDown() {
        for (String s : schemas) {
            jdbc.update("DELETE FROM public.whatsapp_campaign_jobs WHERE tenant_id = (SELECT id FROM public.tenants "
                    + "WHERE schema_name = ?)", s);
            jdbc.update("DELETE FROM public.whatsapp_accounts WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                    + "schema_name = ?)", s);
            moduleRegistry.evict(s);
        }
        for (UUID u : users) {
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", u);
        }
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private String newTenant() {
        String s = tx.execute(st -> tenantFactory.create()).getSchemaName();
        schemas.add(s);
        return s;
    }

    private Member owner(String tenant) {
        UUID user = UUID.randomUUID();
        String phone = "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                + "phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, 'OWNER')", user, phone);
        users.add(user);
        return new Member(user, tenant, TestAccess.token(jdbc, jwtTokenProvider, user, tenant, "OWNER"));
    }

    private Member employee(String tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + tenant + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", id, store);
        return new Member(id, tenant, TestAccess.token(jdbc, jwtTokenProvider, id, tenant, "EMPLOYEE", "ACTIVE",
                store, false));
    }

    private static String metaId() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L));
    }

    private MvcResult rest(String jwt, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + jwt);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        MvcResult r = mockMvc.perform(request).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return r;
    }

    private MvcResult rest(Member m, HttpMethod method, String path, Object body) throws Exception {
        return rest(m.jwt(), method, path, body);
    }

    private JsonNode data(MvcResult r, int status) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        return text.isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(text).path("data");
    }

    private static void assertError(MvcResult r, int status, String domainCode) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        assertThat(text).contains("\"domainCode\":\"" + domainCode + "\"");
    }

    private static void assertBad(MvcResult r) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(400);
    }

    private String connect(Member m) throws Exception {
        String n = metaId();
        JsonNode d = data(rest(m, HttpMethod.POST, "/api/v1/whatsapp/account",
                Map.of("code", "signup-" + UUID.randomUUID(), "wabaId", metaId(), "phoneNumberId", n)), 200);
        assertThat(d.path("connected").asBoolean()).isTrue();
        return n;
    }

    /** An APPROVED template of the company (as if Meta approved it). */
    private UUID template(String tenant, String name, String header, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + tenant + "\".whatsapp_templates (id, name, language, category, header_format, "
                + "body_text, status, meta_template_id) VALUES (?, ?, 'fr', 'MARKETING', ?, ?, 'APPROVED', ?)", id, name,
                header, body, metaId());
        return id;
    }

    /** A client of the company; {@code consent} = team consent given. */
    private UUID client(String tenant, String name, String e164, UUID storeId, boolean consent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + tenant + "\".clients (id, name, phone, phone_normalized, store_id, "
                + "marketing_consent_at, archived, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, false, now(), now())",
                id, name, e164, e164, storeId, consent ? java.sql.Timestamp.from(Instant.now()) : null);
        return id;
    }

    private String webhookJson(String phoneNumberId, List<Map<String, Object>> messages,
                               List<Map<String, Object>> statuses) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("messaging_product", "whatsapp");
        value.put("metadata", Map.of("display_phone_number", "237690000000", "phone_number_id", phoneNumberId));
        if (messages != null) {
            value.put("messages", messages);
        }
        if (statuses != null) {
            value.put("statuses", statuses);
        }
        return objectMapper.writeValueAsString(Map.of("object", "whatsapp_business_account", "entry",
                List.of(Map.of("id", "0", "changes", List.of(Map.of("field", "messages", "value", value))))));
    }

    private int signed(String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/whatsapp/webhook")
                .contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8))
                .header("X-Hub-Signature-256", MetaWebhookSignature.header(SECRET,
                        json.getBytes(StandardCharsets.UTF_8)))).andReturn().getResponse().getStatus();
    }

    private static Map<String, Object> text(String from, String body) {
        return Map.of("from", from, "id", "wamid.T" + UUID.randomUUID(), "timestamp",
                String.valueOf(Instant.now().getEpochSecond()), "type", "text", "text", Map.of("body", body));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql.replace("%s", "\"" + schema + "\""), Long.class, args);
        return n == null ? 0 : n;
    }

    /** Runs the dispatcher until the campaign leaves SENDING (≤ 15 s). */
    private String runUntilSent(UUID campaign) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        String status;
        do {
            dispatcher.dispatchDue();
            status = jdbc.queryForObject("SELECT status FROM \"" + schema + "\".whatsapp_campaigns WHERE id = ?",
                    String.class, campaign);
            if (!"SENDING".equals(status)) {
                return status;
            }
            Thread.sleep(220);
        } while (System.currentTimeMillis() < end);
        return status;
    }

    private UUID createCampaign(Map<String, Object> body) throws Exception {
        return UUID.fromString(data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns", body), 201)
                .path("id").asText());
    }

    // ── campaigns ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("send: only consenting clients, one per phone, asynchronous batches from the company number, statuses → counters")
    void sendsToConsentingClientsOnly() throws Exception {
        UUID tpl = template(schema, "promo_" + metaId(), "NONE", "Bonjour {{1}}, -20 % chez {{2}} !");
        client(schema, "Awa", "+237690000101", store, true);
        client(schema, "Awa bis", "+237690000101", null, true); // same phone, legacy client: one message
        client(schema, "Ben", "+237690000102", store, true);
        client(schema, "Carl", "+237690000103", store, true);
        client(schema, "Dora", "+237690000104", store, false); // no consent
        UUID stopped = client(schema, "Eve", "+237690000105", store, true);
        jdbc.update("UPDATE \"" + schema + "\".clients SET marketing_opt_out_at = now() WHERE id = ?", stopped);

        JsonNode preview = data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting",
                Map.of()), 200);
        assertThat(preview.path("count").asInt()).isEqualTo(3);

        UUID campaign = createCampaign(Map.of("name", "Soldes", "templateId", tpl.toString(), "variables",
                List.of(Map.of("source", "clientName", "value", "cher client"), Map.of("source", "storeName"))));
        JsonNode created = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/campaigns/" + campaign, null), 200);
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("statusLabel").asText()).isEqualTo("Brouillon");
        assertThat(created.path("targetedCount").asInt()).isEqualTo(3);

        int before = fake.calls("send-template", number).size();
        JsonNode sending = data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + campaign + "/send", null),
                200);
        assertThat(sending.path("status").asText()).isEqualTo("SENDING");
        assertThat(fake.calls("send-template", number)).hasSize(before); // nothing sent in the request
        assertError(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + campaign + "/send", null), 409,
                "CAMPAIGN_ALREADY_SENT");

        assertThat(runUntilSent(campaign)).isEqualTo("SENT");
        List<FakeMetaGraphClient.Call> sent = fake.calls("send-template", number).subList(before,
                fake.calls("send-template", number).size());
        assertThat(sent).extracting(FakeMetaGraphClient.Call::detail).allMatch(d -> d.contains(":fr:1"))
                .hasSize(3);
        assertThat(sent).extracting(c -> c.detail().split(":")[0])
                .containsExactlyInAnyOrder("237690000101", "237690000102", "237690000103");
        JsonNode done = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/campaigns/" + campaign, null), 200);
        assertThat(done.path("statusLabel").asText()).isEqualTo("Envoyée");
        assertThat(done.path("sentCount").asInt()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM %s.whatsapp_messages WHERE campaign_id = ?", campaign)).isEqualTo(3);
        String storeName = jdbc.queryForObject("SELECT name FROM \"" + schema + "\".stores WHERE id = ?", String.class,
                store);
        assertThat(jdbc.queryForObject("SELECT body FROM \"" + schema + "\".whatsapp_messages WHERE campaign_id = ? "
                + "AND contact_phone = '237690000102'", String.class, campaign))
                .isEqualTo("Bonjour Ben, -20 % chez " + storeName + " !");
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(used), 0) FROM public.usage_counters WHERE tenant_id = "
                + "(SELECT id FROM public.tenants WHERE schema_name = ?) AND metric = 'WHATSAPP_MESSAGES' AND period = "
                + "'MONTH'", Long.class, schema)).as("messages counted on the quota").isEqualTo(3);

        // Meta's statuses move the counters once, never back
        String wa = jdbc.queryForObject("SELECT wa_message_id FROM \"" + schema + "\".whatsapp_messages WHERE "
                + "campaign_id = ? AND contact_phone = '237690000101'", String.class, campaign);
        String delivered = webhookJson(number, null, List.of(Map.of("id", wa, "status", "delivered")));
        assertThat(signed(delivered)).isEqualTo(200);
        assertThat(signed(delivered)).isEqualTo(200);
        assertThat(signed(webhookJson(number, null, List.of(Map.of("id", wa, "status", "read"))))).isEqualTo(200);
        JsonNode counters = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/campaigns/" + campaign, null), 200);
        assertThat(counters.path("deliveredCount").asInt()).isEqualTo(1);
        assertThat(counters.path("readCount").asInt()).isEqualTo(1);
        assertThat(counters.path("sentCount").asInt()).isEqualTo(3);

        JsonNode recipients = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/campaigns/" + campaign
                + "/recipients", null), 200);
        assertThat(recipients).hasSize(3);
        assertThat(recipients.get(0).path("phone").asText()).isEqualTo("+237690000101");
        assertThat(recipients.get(0).path("statusLabel").asText()).isEqualTo("Lu");
        assertThat(recipients.toString()).doesNotContain(campaign.toString());

        assertError(rest(owner, HttpMethod.PATCH, "/api/v1/whatsapp/campaigns/" + campaign, Map.of("name", "X")), 409,
                "CAMPAIGN_ALREADY_SENT");
        assertError(rest(owner, HttpMethod.DELETE, "/api/v1/whatsapp/campaigns/" + campaign, null), 409,
                "CAMPAIGN_ALREADY_SENT");
        assertThat(count("SELECT count(*) FROM %s.audit_log WHERE action IN ('WHATSAPP_CAMPAIGN_CREATED', "
                + "'WHATSAPP_CAMPAIGN_SEND_STARTED', 'WHATSAPP_CAMPAIGN_COMPLETED')")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.whatsapp_campaign_jobs WHERE campaign_id = ?",
                Long.class, campaign)).isZero();
    }

    @Test
    @DisplayName("consent withdrawn after the start: SKIPPED at send time; Meta refusal FAILED, timeout UNCERTAIN, rate limit retried")
    void consentRecheckedAndMetaOutcomes() throws Exception {
        UUID tpl = template(schema, "relance_" + metaId(), "NONE", "Bonjour, nouveautés en boutique !");
        UUID withdrawn = client(schema, "Awa", "+237690000201", store, true);
        client(schema, "Refused", "+237999000001", store, true);  // fake: Meta refuses
        client(schema, "Timeout", "+237998000001", store, true);  // fake: no answer
        client(schema, "Ok", "+237690000202", store, true);
        UUID campaign = createCampaign(Map.of("name", "Relance", "templateId", tpl.toString()));
        data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + campaign + "/send", null), 200);
        jdbc.update("UPDATE \"" + schema + "\".clients SET marketing_consent_at = NULL WHERE id = ?", withdrawn);

        assertThat(runUntilSent(campaign)).isEqualTo("SENT");
        Map<String, String> byPhone = new HashMap<>();
        jdbc.query("SELECT phone, status FROM \"" + schema + "\".whatsapp_campaign_recipients WHERE campaign_id = ?",
                rs -> {
                    byPhone.put(rs.getString(1), rs.getString(2));
                }, campaign);
        assertThat(byPhone).containsEntry("237690000201", "SKIPPED").containsEntry("237999000001", "FAILED")
                .containsEntry("237998000001", "UNCERTAIN").containsEntry("237690000202", "SENT");
        JsonNode c = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/campaigns/" + campaign, null), 200);
        assertThat(c.path("sentCount").asInt()).isEqualTo(1);
        assertThat(c.path("skippedCount").asInt()).isEqualTo(1);
        assertThat(c.path("failedCount").asInt()).isEqualTo(1);
        assertThat(c.path("uncertainCount").asInt()).isEqualTo(1);
        assertThat(fake.calls("send-template", number)).extracting(x -> x.detail().split(":")[0])
                .doesNotContain("237690000201");
        JsonNode recipients = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/campaigns/" + campaign
                + "/recipients", null), 200);
        assertThat(recipients.toString()).contains("Le client ne souhaite plus recevoir d'offres")
                .contains("Envoi incertain");
    }

    @Test
    @DisplayName("Meta's throughput limit: the recipient goes back to PENDING and the campaign pauses (nothing lost, nothing doubled)")
    void rateLimitPauses() throws Exception {
        UUID tpl = template(schema, "limite_" + metaId(), "NONE", "Promo !");
        client(schema, "Limited", "+237997000001", store, true); // fake: 130429
        UUID campaign = createCampaign(Map.of("name", "Limite", "templateId", tpl.toString()));
        data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + campaign + "/send", null), 200);
        dispatcher.dispatchDue();
        assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".whatsapp_campaign_recipients WHERE "
                + "campaign_id = ?", String.class, campaign)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT next_run_at > now() + interval '50 seconds' FROM "
                + "public.whatsapp_campaign_jobs WHERE campaign_id = ?", Boolean.class, campaign)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".whatsapp_campaigns WHERE id = ?",
                String.class, campaign)).isEqualTo("SENDING");
    }

    @Test
    @DisplayName("refusals at send: no template 422, nobody eligible 422, another campaign SENDING 409; scheduled then cancelled")
    void sendRefusalsAndSchedule() throws Exception {
        UUID noTemplate = createCampaign(Map.of("name", "Vide"));
        assertError(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + noTemplate + "/send", null), 422,
                "CAMPAIGN_NOT_READY");
        UUID tpl = template(schema, "vide_" + metaId(), "NONE", "Promo !");
        UUID nobody = createCampaign(Map.of("name", "Personne", "templateId", tpl.toString()));
        assertError(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + nobody + "/send", null), 422,
                "CAMPAIGN_NO_RECIPIENT");

        client(schema, "Rate", "+237997000002", store, true); // stays PENDING (rate limit): campaign stays SENDING
        UUID first = createCampaign(Map.of("name", "Un", "templateId", tpl.toString()));
        data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + first + "/send", null), 200);
        UUID second = createCampaign(Map.of("name", "Deux", "templateId", tpl.toString()));
        assertError(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + second + "/send", null), 409,
                "CAMPAIGN_SENDING_IN_PROGRESS");

        String at = Instant.now().plusSeconds(3600).toString();
        JsonNode scheduled = data(rest(owner, HttpMethod.PATCH, "/api/v1/whatsapp/campaigns/" + second,
                Map.of("scheduledAt", at)), 200);
        assertThat(scheduled.path("statusLabel").asText()).isEqualTo("Programmée");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.whatsapp_campaign_jobs WHERE campaign_id = ?",
                Long.class, second)).isEqualTo(1);
        JsonNode cancelled = data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + second + "/cancel",
                null), 200);
        assertThat(cancelled.path("statusLabel").asText()).isEqualTo("Annulée");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.whatsapp_campaign_jobs WHERE campaign_id = ?",
                Long.class, second)).isZero();
        assertThat(rest(owner, HttpMethod.DELETE, "/api/v1/whatsapp/campaigns/" + second, null).getResponse()
                .getStatus()).isEqualTo(204);
        assertBad(rest(owner, HttpMethod.PATCH, "/api/v1/whatsapp/campaigns/" + noTemplate,
                Map.of("scheduledAt", Instant.now().minusSeconds(60).toString())));
    }

    @Test
    @DisplayName("targeting stores: only the clients of the chosen store are counted and messaged (legacy clients excluded)")
    void storeTargeting() throws Exception {
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name) VALUES (?, 'Boutique Nord')", other);
        UUID tpl = template(schema, "cible_" + metaId(), "NONE", "Promo {{1}} !");
        client(schema, "Centre", "+237690000601", store, true);
        client(schema, "Nord", "+237690000602", other, true);
        client(schema, "Ancien", "+237690000603", null, true);
        assertThat(data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting",
                Map.of("storeIds", List.of(other.toString()))), 200).path("count").asInt()).isEqualTo(1);
        assertThat(data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting", Map.of()), 200)
                .path("count").asInt()).isEqualTo(3);
        UUID campaign = createCampaign(Map.of("name", "Nord", "templateId", tpl.toString(), "storeIds",
                List.of(other.toString()), "variables", List.of(Map.of("source", "storeName"))));
        JsonNode created = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/campaigns/" + campaign, null), 200);
        assertThat(created.path("storeNames").toString()).contains("Boutique Nord");
        int before = fake.calls("send-template", number).size();
        data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + campaign + "/send", null), 200);
        assertThat(runUntilSent(campaign)).isEqualTo("SENT");
        assertThat(fake.calls("send-template", number).subList(before, fake.calls("send-template", number).size()))
                .extracting(c -> c.detail().split(":")[0]).containsExactly("237690000602");
        assertThat(jdbc.queryForObject("SELECT body FROM \"" + schema + "\".whatsapp_messages WHERE campaign_id = ?",
                String.class, campaign)).isEqualTo("Promo Boutique Nord !");
    }

    @Test
    @DisplayName("platform templates for the super-admin: Meta's listing with header, body and variable count, approved first")
    void platformTemplates() throws Exception {
        UUID admin = UUID.randomUUID();
        String adminJwt = TestAccess.token(jdbc, jwtTokenProvider, admin, "public", "SUPER_ADMIN");
        users.add(admin);
        String name = "annonce_" + metaId();
        fake.createTemplate("200000000000001", "t", new com.keevo.whatsappcloud.graph.domain.model.TemplateDraft(
                name, "MARKETING", "fr", "IMAGE", null, "h", "Bonjour {{1}} !", List.of("Awa"), null));
        fake.review("200000000000001", name, "fr", "APPROVED", null);
        JsonNode list = data(rest(adminJwt, HttpMethod.GET, "/api/v1/admin/whatsapp/templates", null), 200);
        JsonNode t = null;
        for (JsonNode n : list) {
            if (name.equals(n.path("name").asText())) {
                t = n;
            }
        }
        assertThat(t).isNotNull();
        assertThat(t.path("statusLabel").asText()).isEqualTo("Approuvé");
        assertThat(t.path("headerFormat").asText()).isEqualTo("IMAGE");
        assertThat(t.path("variableCount").asInt()).isEqualTo(1);
        assertThat(list.get(0).path("status").asText()).isEqualTo("APPROVED");
        assertThat(rest(owner, HttpMethod.GET, "/api/v1/admin/whatsapp/templates", null).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a scheduled campaign that is due is started by the dispatcher")
    void scheduledCampaignStarts() throws Exception {
        UUID tpl = template(schema, "prog_" + metaId(), "NONE", "Promo !");
        client(schema, "Awa", "+237690000301", store, true);
        UUID campaign = createCampaign(Map.of("name", "Plus tard", "templateId", tpl.toString(), "scheduledAt",
                Instant.now().plusSeconds(120).toString()));
        jdbc.update("UPDATE \"" + schema + "\".whatsapp_campaigns SET scheduled_at = now() - interval '1 second' "
                + "WHERE id = ?", campaign);
        jdbc.update("UPDATE public.whatsapp_campaign_jobs SET next_run_at = now() - interval '1 second' WHERE "
                + "campaign_id = ?", campaign);
        dispatcher.dispatchDue();
        assertThat(runUntilSent(campaign)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("isolation and rights: another company's campaign 404, employee 403, unknown store 400")
    void isolationAndRights() throws Exception {
        UUID campaign = createCampaign(Map.of("name", "Privée"));
        String other = newTenant();
        Member otherOwner = owner(other);
        assertError(rest(otherOwner, HttpMethod.GET, "/api/v1/whatsapp/campaigns/" + campaign, null), 404,
                "CAMPAIGN_NOT_FOUND");
        assertThat(rest(employee(schema), HttpMethod.GET, "/api/v1/whatsapp/campaigns", null).getResponse()
                .getStatus()).isEqualTo(403);
        assertBad(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting",
                Map.of("storeIds", List.of(UUID.randomUUID().toString()))));
    }

    // ── media header ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("media-header template: uploaded media id or https link at send; missing / non-https refused")
    void mediaHeaderSends() throws Exception {
        UUID tpl = template(schema, "affiche_" + metaId(), "IMAGE", "Nouvelle collection {{1}} !");
        String contact = "237690000401";
        MvcResult upload = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/whatsapp/media")
                .file(new MockMultipartFile("file", "affiche.png", "image/png", new byte[]{1, 2, 3}))
                .header("Authorization", "Bearer " + owner.jwt())).andReturn();
        String mediaId = data(upload, 200).path("mediaId").asText();
        assertThat(mediaId).startsWith("fake-media-");
        assertThat(fake.calls("upload-media", number)).hasSize(1);

        String path = "/api/v1/whatsapp/conversations/" + contact + "/template";
        assertBad(rest(owner, HttpMethod.POST, path, Map.of("templateId", tpl.toString(), "variables",
                List.of("été"))));
        assertBad(rest(owner, HttpMethod.POST, path, Map.of("templateId", tpl.toString(), "variables",
                List.of("été"), "headerMediaLink", "http://exemple.cm/a.png")));
        int before = fake.calls("send-template", number).size();
        data(rest(owner, HttpMethod.POST, path, Map.of("templateId", tpl.toString(), "variables", List.of("été"),
                "headerMediaId", mediaId)), 200);
        data(rest(owner, HttpMethod.POST, path, Map.of("templateId", tpl.toString(), "variables", List.of("été"),
                "headerMediaLink", "https://exemple.cm/a.png")), 200);
        assertThat(fake.calls("send-template", number)).hasSize(before + 2)
                .last().extracting(FakeMetaGraphClient.Call::detail).asString().endsWith(":2"); // header + body

        UUID textTpl = template(schema, "texte_" + metaId(), "NONE", "Bonjour");
        assertBad(rest(owner, HttpMethod.POST, path, Map.of("templateId", textTpl.toString(), "headerMediaId",
                mediaId)));

        // a media campaign needs its media before it leaves
        client(schema, "Awa", "+237690000402", store, true);
        UUID campaign = createCampaign(Map.of("name", "Affiche", "templateId", tpl.toString(), "variables",
                List.of(Map.of("source", "static", "value", "printemps"))));
        assertError(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + campaign + "/send", null), 422,
                "CAMPAIGN_NOT_READY");
        data(rest(owner, HttpMethod.PATCH, "/api/v1/whatsapp/campaigns/" + campaign, Map.of("headerMediaId", mediaId)),
                200);
        data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + campaign + "/send", null), 200);
        assertThat(runUntilSent(campaign)).isEqualTo("SENT");
    }

    // ── STOP / START ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("STOP on the company number: the client's opt-out set (every store), one confirmation per minute; START clears it")
    void stopAndStartOnCompanyNumber() throws Exception {
        UUID a = client(schema, "Awa", "+237690000501", store, true);
        UUID b = client(schema, "Awa ancienne", "+237690000501", null, true);
        int before = fake.calls("send-text", number).size();
        assertThat(signed(webhookJson(number, List.of(text("237690000501", " Stop ")), null))).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM %s.clients WHERE id IN (?, ?) AND marketing_opt_out_at IS NOT NULL",
                a, b)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM %s.clients WHERE id = ? AND marketing_consent_at IS NOT NULL", a))
                .isEqualTo(1);
        long end = System.currentTimeMillis() + 5_000;
        while (fake.calls("send-text", number).size() == before && System.currentTimeMillis() < end) {
            Thread.sleep(25);
        }
        assertThat(fake.calls("send-text", number)).hasSize(before + 1);
        JsonNode client = data(rest(owner, HttpMethod.GET, "/api/v1/clients/" + a, null), 200);
        assertThat(client.path("marketingEligible").asBoolean()).isFalse();

        assertThat(signed(webhookJson(number, List.of(text("237690000501", "ARRÊTER")), null))).isEqualTo(200);
        Thread.sleep(300);
        assertThat(fake.calls("send-text", number)).as("one confirmation per minute").hasSize(before + 1);

        assertThat(signed(webhookJson(number, List.of(text("237690000501", "start")), null))).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM %s.clients WHERE id IN (?, ?) AND marketing_opt_out_at IS NULL", a, b))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM %s.whatsapp_messages WHERE contact_phone = '237690000501' AND "
                + "direction = 'IN'")).isEqualTo(3);
        assertThat(signed(webhookJson(number, List.of(text("237690000501", "stop les promos svp")), null)))
                .isEqualTo(200);
        assertThat(count("SELECT count(*) FROM %s.clients WHERE id = ? AND marketing_opt_out_at IS NULL", a))
                .as("a sentence is not a keyword").isEqualTo(1);
    }

    // ── platform number ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("platform number: inbox for the super-admin only, STOP recorded on the platform list, reply in the window")
    void platformInboxAndStop() throws Exception {
        String contact = "2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        UUID admin = UUID.randomUUID();
        String adminJwt = TestAccess.token(jdbc, jwtTokenProvider, admin, "public", "SUPER_ADMIN");
        users.add(admin);
        try {
            assertThat(signed(webhookJson(PLATFORM_NUMBER, List.of(text(contact, "Bonjour Keevo")), null)))
                    .isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.platform_whatsapp_messages WHERE "
                    + "contact_phone = ?", Long.class, contact)).isEqualTo(1);
            JsonNode conversations = data(rest(adminJwt, HttpMethod.GET, "/api/v1/admin/whatsapp/conversations",
                    null), 200);
            assertThat(conversations.toString()).contains(contact);
            assertThat(rest(owner, HttpMethod.GET, "/api/v1/admin/whatsapp/conversations", null).getResponse()
                    .getStatus()).isEqualTo(403);
            int before = fake.calls("send-text", PLATFORM_NUMBER).size();
            data(rest(adminJwt, HttpMethod.POST, "/api/v1/admin/whatsapp/conversations/" + contact + "/messages",
                    Map.of("text", "Bonjour, comment pouvons-nous vous aider ?")), 200);
            assertThat(fake.calls("send-text", PLATFORM_NUMBER)).hasSize(before + 1);

            assertThat(signed(webhookJson(PLATFORM_NUMBER, List.of(text(contact, "STOP")), null))).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.whatsapp_marketing_opt_outs WHERE phone = ?",
                    Long.class, contact)).isEqualTo(1);
            assertThat(signed(webhookJson(PLATFORM_NUMBER, List.of(text(contact, "Reprendre")), null)))
                    .isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.whatsapp_marketing_opt_outs WHERE phone = ?",
                    Long.class, contact)).isZero();
        } finally {
            jdbc.update("DELETE FROM public.platform_whatsapp_messages WHERE contact_phone = ?", contact);
            jdbc.update("DELETE FROM public.platform_whatsapp_conversations WHERE contact_phone = ?", contact);
            jdbc.update("DELETE FROM public.whatsapp_marketing_opt_outs WHERE phone = ?", contact);
        }
    }

    // ── Story 21.11: purchase-behaviour targeting ─────────────────────────────────────────────────────────

    private void sale(UUID storeId, UUID client, int total, String status, Instant at) {
        jdbc.update("INSERT INTO \"" + schema + "\".sales (id, created_at, employee_id, occurred_at, payment_mode, "
                        + "status, store_id, total_amount, discount_amount, client_id) VALUES (?, ?, ?, ?, 'CASH', ?, ?, ?, "
                        + "0, ?)", UUID.randomUUID(), java.sql.Timestamp.from(at), UUID.randomUUID(),
                java.sql.Timestamp.from(at), status, storeId, total, client);
    }

    private UUID invoice(UUID storeId, UUID client, long total, String kind, Instant at) {
        UUID id = UUID.randomUUID();
        boolean quote = "QUOTE".equals(kind);
        jdbc.update("INSERT INTO \"" + schema + "\".invoices (id, number, status, issue_date, issued_at, client_name, "
                        + "client_id, store_id, vat_registered, vat_rate, prices_include_vat, total_ht, total_vat, "
                        + "total_ttc, created_by, kind) VALUES (?, ?, ?, ?, ?, 'Client', ?, ?, false, 0, true, ?, 0, ?, "
                        + "?, ?)", id, quote ? null : "F" + metaId(), quote ? "DRAFT" : "ISSUED",
                quote ? null : java.sql.Date.valueOf(java.time.LocalDate.ofInstant(at, java.time.ZoneOffset.UTC)),
                quote ? null : java.sql.Timestamp.from(at), client, storeId, total, total, owner.user(), kind);
        return id;
    }

    private int previewCount(Map<String, Object> body) throws Exception {
        return data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting", body), 200)
                .path("count").asInt();
    }

    private JsonNode patch(UUID campaign, String json) throws Exception {
        var request = MockMvcRequestBuilders.patch("/api/v1/whatsapp/campaigns/" + campaign)
                .header("Authorization", "Bearer " + owner.jwt()).contentType(MediaType.APPLICATION_JSON).content(json);
        MvcResult r = mockMvc.perform(request).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return data(r, 200);
    }

    @Test
    @DisplayName("21.11 purchase criteria: inactive, recent, total spent, number of purchases over sales + invoices − credit notes, per number, per store; frozen at send")
    void purchaseTargeting() throws Exception {
        provisioner.ensureProvisioned(schema, "invoicing");
        UUID north = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name) VALUES (?, 'Boutique Nord')", north);
        Instant now = Instant.now();
        java.time.Duration day = java.time.Duration.ofDays(1);
        // A: two POS sales (5 and 10 days ago), 15 000 in all
        UUID a = client(schema, "Awa", "+237690000701", store, true);
        sale(store, a, 7_500, "COMPLETED", now.minus(day.multipliedBy(5)));
        sale(store, a, 7_500, "COMPLETED", now.minus(day.multipliedBy(10)));
        // B: one invoice 40 days ago; a quote and a cancelled sale yesterday are not purchases
        UUID b = client(schema, "Ben", "+237690000702", store, true);
        invoice(store, b, 3_000, "INVOICE", now.minus(day.multipliedBy(40)));
        invoice(store, b, 50_000, "QUOTE", now.minus(day));
        sale(store, b, 9_000, "CANCELLED", now.minus(day));
        // C: never bought
        client(schema, "Carl", "+237690000703", store, true);
        // D: invoice 10 000 50 days ago, credit note 4 000 → total 6 000, one purchase
        UUID d = client(schema, "Dora", "+237690000704", store, true);
        UUID inv = invoice(store, d, 10_000, "INVOICE", now.minus(day.multipliedBy(50)));
        jdbc.update("INSERT INTO \"" + schema + "\".credit_notes (id, number, invoice_id, reason, total_ht, total_vat, "
                + "total_ttc, issued_at) VALUES (?, ?, ?, 'Retour', 4000, 0, 4000, ?)", UUID.randomUUID(),
                "A" + metaId(), inv, java.sql.Timestamp.from(now.minus(day.multipliedBy(49))));
        // E: one number, two records — bought yesterday in the main store, nothing in Boutique Nord
        UUID e1 = client(schema, "Eve", "+237690000705", store, true);
        client(schema, "Eve Nord", "+237690000705", north, true);
        sale(store, e1, 2_000, "COMPLETED", now.minus(day));
        // F: no consent, never bought — never targeted
        client(schema, "Fred", "+237690000706", store, false);
        // G: invoice 8 000 50 days ago, refunded 1 000 yesterday — a credit note is not a purchase (still inactive)
        UUID g = client(schema, "Gina", "+237690000707", store, true);
        UUID invG = invoice(store, g, 8_000, "INVOICE", now.minus(day.multipliedBy(50)));
        jdbc.update("INSERT INTO \"" + schema + "\".credit_notes (id, number, invoice_id, reason, total_ht, total_vat, "
                + "total_ttc, issued_at) VALUES (?, ?, ?, 'Geste', 1000, 0, 1000, ?)", UUID.randomUUID(),
                "A" + metaId(), invG, java.sql.Timestamp.from(now.minus(day)));

        assertThat(previewCount(Map.of())).as("no criterion: the 21.6 audience").isEqualTo(6);
        assertThat(previewCount(Map.of("inactiveForDays", 0))).as("0 days = not set (Bref)").isEqualTo(6);
        assertThat(previewCount(Map.of("totalInvoicesMin", 0, "totalSpentMin", 0))).as("0 minimums = not set")
                .isEqualTo(6);
        assertThat(previewCount(Map.of("inactiveForDays", 30))).as("B, C, D, G (E bought yesterday; G's credit "
                + "note of yesterday is no purchase)").isEqualTo(4);
        assertThat(previewCount(Map.of("purchasedWithinDays", 30))).as("A, E").isEqualTo(2);
        assertThat(previewCount(Map.of("purchasedWithinDays", 60, "inactiveForDays", 30))).as("B, D, G").isEqualTo(3);
        assertThat(previewCount(Map.of("totalSpentMin", 10_000))).as("A only: D is 6 000 after its credit note")
                .isEqualTo(1);
        assertThat(previewCount(Map.of("totalSpentMin", 5_999.5, "totalSpentMax", 6_000.7))).as("D, decimals rounded")
                .isEqualTo(1);
        MvcResult noWholeAmount = rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting",
                Map.of("totalSpentMin", 0.3, "totalSpentMax", 0.7));
        assertBad(noWholeAmount);
        assertThat(noWholeAmount.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Aucun total d'achats en francs entiers");
        assertThat(previewCount(Map.of("totalInvoicesMin", 2))).as("A").isEqualTo(1);
        assertThat(previewCount(Map.of("totalInvoicesMax", 0))).as("C: the quote and the cancelled sale do not count")
                .isEqualTo(1);
        assertThat(previewCount(Map.of("storeIds", List.of(north.toString()), "inactiveForDays", 30)))
                .as("Boutique Nord: E has bought nothing there").isEqualTo(1);
        assertThat(previewCount(Map.of("storeIds", List.of(store.toString()), "inactiveForDays", 30)))
                .as("main store: B, C, D, G").isEqualTo(4);

        assertBad(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting",
                Map.of("inactiveForDays", 4000)));
        assertBad(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting",
                Map.of("inactiveForDays", 1.5)));
        assertBad(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting",
                Map.of("totalSpentMin", "abc")));
        assertBad(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting",
                Map.of("totalInvoicesMin", -1)));
        MvcResult huge = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/whatsapp/campaigns/preview-targeting")
                .header("Authorization", "Bearer " + owner.jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"totalSpentMin\": 1e400}")).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        assertBad(huge);
        MvcResult minMax = rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting",
                Map.of("totalSpentMin", 5_000, "totalSpentMax", 1_000));
        assertBad(minMax);
        assertThat(minMax.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Le montant minimum des achats ne peut pas dépasser le maximum");
        assertBad(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/preview-targeting",
                Map.of("purchasedWithinDays", 10, "inactiveForDays", 30)));

        UUID tpl = template(schema, "relance_" + metaId(), "NONE", "Vous nous manquez {{1}} !");
        UUID campaign = createCampaign(Map.of("name", "Relance", "templateId", tpl.toString(), "variables",
                List.of(Map.of("source", "clientName", "value", "cher client")), "inactiveForDays", 30));
        JsonNode created = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/campaigns/" + campaign, null), 200);
        assertThat(created.path("targetedCount").asInt()).isEqualTo(4);
        assertThat(created.path("inactiveForDays").asInt()).isEqualTo(30);
        assertThat(created.path("totalSpentMin").isNull()).isTrue();
        assertThat(created.path("purchaseCriteriaLabels").toString()).contains("Sans achat depuis 30 jours");

        JsonNode both = patch(campaign, "{\"totalSpentMin\": 5000}");
        assertThat(both.path("inactiveForDays").asInt()).as("absent field kept").isEqualTo(30);
        assertThat(both.path("targetedCount").asInt()).as("inactive and ≥ 5 000: D, G").isEqualTo(2);
        assertThat(both.path("purchaseCriteriaLabels")).hasSize(2);
        JsonNode cleared = patch(campaign, "{\"inactiveForDays\": null}");
        assertThat(cleared.path("inactiveForDays").isNull()).as("present null clears").isTrue();
        assertThat(cleared.path("targetedCount").asInt()).as("≥ 5 000: A, D, G").isEqualTo(3);
        JsonNode back = patch(campaign, "{\"inactiveForDays\": 30, \"totalSpentMin\": null, \"name\": \"Relance 30 j\"}");
        assertThat(back.path("targetedCount").asInt()).isEqualTo(4);
        assertThat(back.path("totalSpentMin").isNull()).isTrue();
        assertThat(rest(owner, HttpMethod.PATCH, "/api/v1/whatsapp/campaigns/" + campaign,
                Map.of("totalInvoicesMin", 3, "totalInvoicesMax", 1)).getResponse().getStatus()).isEqualTo(400);

        // frozen at start: a purchase by B after the start does not change who receives it
        int before = fake.calls("send-template", number).size();
        data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/campaigns/" + campaign + "/send", null), 200);
        sale(store, b, 1_000, "COMPLETED", Instant.now());
        assertThat(runUntilSent(campaign)).isEqualTo("SENT");
        assertThat(fake.calls("send-template", number).subList(before, fake.calls("send-template", number).size()))
                .extracting(c -> c.detail().split(":")[0])
                .containsExactlyInAnyOrder("237690000702", "237690000703", "237690000704", "237690000707");
        JsonNode sent = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/campaigns/" + campaign, null), 200);
        assertThat(sent.path("sentCount").asInt()).isEqualTo(4);
        assertThat(sent.toString()).doesNotContain(b.toString()).doesNotContain(d.toString());
        assertThat(previewCount(Map.of("inactiveForDays", 30))).as("B bought: no longer inactive").isEqualTo(3);
    }
}
