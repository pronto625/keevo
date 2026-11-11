package com.keevo.admin.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.admin.campaign.application.EmailChannelSender;
import com.keevo.admin.campaign.application.PlatformCampaignDispatcher;
import com.keevo.admin.campaign.application.SmsChannelSender;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.capability.notification.SmsSender;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 21.6 — the super-admin's platform campaigns end to end on PostgreSQL: segment by plan, store country and
 * activity (only the matching company's owner), the four channels (push kept in the app inbox, WhatsApp template from
 * the platform number on the fake Graph API, SMS / email on recording doubles), counters per channel, Meta's statuses
 * on the platform number, STOP recorded on the platform list skipped at send time, audited history, owner refused.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Platform campaigns of the super-admin on the full context (21.6)")
class PlatformCampaignFullContextTest extends FullContextIntegrationTest {

    static final String SECRET = "test-whatsapp-app-secret";
    static final String PLATFORM_NUMBER = "100000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetaGraphClient graph;
    @Autowired PlatformCampaignDispatcher dispatcher;
    @Autowired SmsChannelSender smsChannel;
    @Autowired EmailChannelSender emailChannel;

    /** Records the SMS / emails instead of a provider. */
    static final class Recorder implements SmsSender, EmailSender {
        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public DeliveryResult send(SmsMessage message) {
            sent.add("sms:" + message.to() + ":" + message.text());
            return DeliveryResult.deliveredBy("test");
        }

        @Override
        public DeliveryResult send(EmailMessage message) {
            sent.add("email:" + message.to() + ":" + message.subject());
            return DeliveryResult.deliveredBy("test");
        }
    }

    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> campaigns = new ArrayList<>();
    private final Recorder recorder = new Recorder();
    private Object realSms;
    private Object realEmail;
    private FakeMetaGraphClient fake;
    private String schemaA;
    private String schemaB;
    private UUID tenantA;
    private UUID tenantB;
    private UUID ownerA;
    private String phoneA;
    private String adminJwt;
    private String ownerJwt;

    @BeforeEach
    void setUp() {
        fake = (FakeMetaGraphClient) AopTestUtils.getUltimateTargetObject(graph);
        realSms = ReflectionTestUtils.getField(smsChannel, "sms");
        realEmail = ReflectionTestUtils.getField(emailChannel, "email");
        ReflectionTestUtils.setField(smsChannel, "sms", recorder);
        ReflectionTestUtils.setField(emailChannel, "email", recorder);
        schemaA = tx.execute(s -> tenantFactory.create()).getSchemaName();
        schemaB = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantA = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schemaA);
        tenantB = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schemaB);
        jdbc.update("UPDATE \"" + schemaA + "\".stores SET country = 'CM', category = 'food'");
        jdbc.update("UPDATE \"" + schemaB + "\".stores SET country = 'CI', category = 'fashion'");
        ownerA = UUID.randomUUID();
        phoneA = "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        user(ownerA, phoneA, "Awa", "Ngono", "awa." + ownerA.toString().substring(0, 8) + "@exemple.cm");
        TestAccess.grant(jdbc, ownerA, schemaA, "OWNER");
        UUID ownerB = UUID.randomUUID();
        user(ownerB, "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)), "Ben", "Kodjo",
                null);
        TestAccess.grant(jdbc, ownerB, schemaB, "OWNER");
        UUID admin = UUID.randomUUID();
        user(admin, "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)), "Sam", "Admin",
                null);
        adminJwt = TestAccess.token(jdbc, jwtTokenProvider, admin, "public", "SUPER_ADMIN");
        ownerJwt = TestAccess.token(jdbc, jwtTokenProvider, ownerA, schemaA, "OWNER");
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(smsChannel, "sms", realSms);
        ReflectionTestUtils.setField(emailChannel, "email", realEmail);
        for (UUID c : campaigns) {
            jdbc.update("DELETE FROM public.platform_campaigns WHERE id = ?", c);
            jdbc.update("DELETE FROM public.platform_campaign_events WHERE campaign_id = ?", c);
            jdbc.update("DELETE FROM public.platform_whatsapp_messages WHERE campaign_id = ?", c);
        }
        jdbc.update("DELETE FROM public.whatsapp_marketing_opt_outs WHERE phone = ?", phoneA.substring(1));
        jdbc.update("DELETE FROM public.platform_whatsapp_conversations WHERE contact_phone = ?", phoneA.substring(1));
        for (UUID u : users) {
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", u);
            jdbc.update("DELETE FROM public.users WHERE id = ?", u);
        }
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void user(UUID id, String phone, String first, String last, String email) {
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                + "phone_number, role, first_name, last_name, email) VALUES (?, now(), now(), true, 0, 'hash', ?, "
                + "'OWNER', ?, ?, ?)", id, phone, first, last, email);
        users.add(id);
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

    private JsonNode data(MvcResult r, int status) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        return text.isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(text).path("data");
    }

    private Map<String, Object> segment() {
        String plan = jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantA);
        return Map.of("planCodes", List.of(plan), "countries", List.of("cm"), "activities", List.of("food"),
                "tenantIds", List.of(tenantA.toString(), tenantB.toString()));
    }

    private UUID create(Map<String, Object> body) throws Exception {
        UUID id = UUID.fromString(data(rest(adminJwt, HttpMethod.POST, "/api/v1/admin/campaigns", body), 201)
                .path("id").asText());
        campaigns.add(id);
        return id;
    }

    private JsonNode runUntilSent(UUID id) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        JsonNode c;
        do {
            dispatcher.dispatchDue();
            c = data(rest(adminJwt, HttpMethod.GET, "/api/v1/admin/campaigns/" + id, null), 200);
            if (!"SENDING".equals(c.path("status").asText())) {
                return c;
            }
            Thread.sleep(220);
        } while (System.currentTimeMillis() < end);
        return c;
    }

    private static Map<String, Integer> sent(JsonNode campaign, String field) {
        Map<String, Integer> out = new LinkedHashMap<>();
        campaign.path("stats").forEach(s -> out.put(s.path("channel").asText(), s.path(field).asInt()));
        return out;
    }

    @Test
    @DisplayName("segment plan + country + activity: the matching owner gets push, WhatsApp, SMS and email; counters, statuses, history")
    void fourChannelsToTheSegment() throws Exception {
        JsonNode preview = data(rest(adminJwt, HttpMethod.POST, "/api/v1/admin/campaigns/preview-targeting",
                Map.of("segment", segment())), 200);
        assertThat(preview.path("owners").asInt()).isEqualTo(1);
        assertThat(preview.path("byChannel").path("EMAIL").asInt()).isEqualTo(1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Nouveautés de novembre");
        body.put("channels", List.of("PUSH", "WHATSAPP", "SMS", "EMAIL"));
        body.put("title", "Du nouveau sur Keevo");
        body.put("message", "Découvrez les campagnes WhatsApp pour vos clients.");
        body.put("segment", segment());
        body.put("whatsapp", Map.of("templateName", "annonce_novembre", "language", "fr", "bodyText",
                "Bonjour {{1}}, du nouveau pour {{2}} !", "variables", List.of(Map.of("source", "firstName"),
                        Map.of("source", "shopName", "value", "votre entreprise"))));
        UUID id = create(body);
        JsonNode created = data(rest(adminJwt, HttpMethod.GET, "/api/v1/admin/campaigns/" + id, null), 200);
        assertThat(created.path("statusLabel").asText()).isEqualTo("Brouillon");
        assertThat(created.path("channelLabels").toString()).contains("Notification", "WhatsApp", "SMS", "E-mail");

        int before = fake.calls("send-template", PLATFORM_NUMBER).size();
        data(rest(adminJwt, HttpMethod.POST, "/api/v1/admin/campaigns/" + id + "/send", null), 200);
        JsonNode done = runUntilSent(id);
        assertThat(done.path("status").asText()).isEqualTo("SENT");
        assertThat(sent(done, "sent")).containsEntry("PUSH", 1).containsEntry("WHATSAPP", 1).containsEntry("SMS", 1)
                .containsEntry("EMAIL", 1);
        assertThat(sent(done, "targeted")).containsEntry("PUSH", 1);

        List<FakeMetaGraphClient.Call> wa = fake.calls("send-template", PLATFORM_NUMBER);
        assertThat(wa).hasSize(before + 1);
        assertThat(wa.get(wa.size() - 1).detail()).startsWith(phoneA.substring(1) + ":annonce_novembre:fr:1");
        assertThat(recorder.sent).anyMatch(s -> s.startsWith("sms:" + phoneA))
                .anyMatch(s -> s.startsWith("email:awa.") && s.endsWith(":Du nouveau sur Keevo"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schemaA + "\".notifications WHERE type = "
                + "'PLATFORM_CAMPAIGN' AND user_id = ?", Long.class, ownerA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT body FROM public.platform_whatsapp_messages WHERE campaign_id = ?",
                String.class, id)).startsWith("Bonjour Awa, du nouveau pour ");

        // Meta's delivery status of the platform message moves the WhatsApp counters
        String waId = jdbc.queryForObject("SELECT wa_message_id FROM public.platform_whatsapp_messages WHERE "
                + "campaign_id = ?", String.class, id);
        String json = objectMapper.writeValueAsString(Map.of("object", "whatsapp_business_account", "entry",
                List.of(Map.of("id", "0", "changes", List.of(Map.of("field", "messages", "value", Map.of(
                        "messaging_product", "whatsapp", "metadata", Map.of("phone_number_id", PLATFORM_NUMBER),
                        "statuses", List.of(Map.of("id", waId, "status", "read")))))))));
        assertThat(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/whatsapp/webhook")
                .contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8))
                .header("X-Hub-Signature-256", MetaWebhookSignature.header(SECRET,
                        json.getBytes(StandardCharsets.UTF_8)))).andReturn().getResponse().getStatus()).isEqualTo(200);
        JsonNode read = data(rest(adminJwt, HttpMethod.GET, "/api/v1/admin/campaigns/" + id, null), 200);
        assertThat(sent(read, "read")).containsEntry("WHATSAPP", 1);
        assertThat(sent(read, "delivered")).containsEntry("WHATSAPP", 1);

        assertThat(read.path("history").findValuesAsText("actionLabel"))
                .containsExactly("Créée", "Envoi lancé", "Envoi terminé");
        assertThat(read.path("history").get(0).path("actorName").asText()).isEqualTo("Sam Admin");
        assertThat(read.path("history").get(2).path("actorName").asText()).isEqualTo("Système");
        assertThat(rest(adminJwt, HttpMethod.POST, "/api/v1/admin/campaigns/" + id + "/send", null).getResponse()
                .getStatus()).isEqualTo(409);
        assertThat(rest(adminJwt, HttpMethod.DELETE, "/api/v1/admin/campaigns/" + id, null).getResponse()
                .getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("an owner who answered STOP to the platform number is skipped at send time")
    void optedOutOwnerSkipped() throws Exception {
        UUID id = create(Map.of("name", "WhatsApp seulement", "channels", List.of("WHATSAPP"), "segment",
                Map.of("tenantIds", List.of(tenantA.toString())), "whatsapp", Map.of("templateName", "rappel",
                        "language", "fr", "bodyText", "Rappel important", "variables", List.of())));
        jdbc.update("INSERT INTO public.whatsapp_marketing_opt_outs (phone) VALUES (?)", phoneA.substring(1));
        int before = fake.calls("send-template", PLATFORM_NUMBER).size();
        data(rest(adminJwt, HttpMethod.POST, "/api/v1/admin/campaigns/" + id + "/send", null), 200);
        JsonNode done = runUntilSent(id);
        assertThat(done.path("status").asText()).isEqualTo("SENT");
        assertThat(sent(done, "skipped")).containsEntry("WHATSAPP", 1);
        assertThat(fake.calls("send-template", PLATFORM_NUMBER)).hasSize(before);
    }

    @Test
    @DisplayName("validation and rights: no channel / unknown activity 400, missing message 422, scheduled → cancelled, owner 403")
    void validationAndRights() throws Exception {
        assertThat(rest(ownerJwt, HttpMethod.GET, "/api/v1/admin/campaigns", null).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(rest(adminJwt, HttpMethod.POST, "/api/v1/admin/campaigns", Map.of("name", "X", "segment",
                Map.of("activities", List.of("inconnue")))).getResponse().getStatus()).isEqualTo(400);
        assertThat(rest(adminJwt, HttpMethod.POST, "/api/v1/admin/campaigns", Map.of("name", "X", "channels",
                List.of("FAX"))).getResponse().getStatus()).isEqualTo(400);
        UUID id = create(Map.of("name", "Sans texte", "channels", List.of("SMS"), "segment",
                Map.of("tenantIds", List.of(tenantA.toString()))));
        MvcResult r = rest(adminJwt, HttpMethod.POST, "/api/v1/admin/campaigns/" + id + "/send", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("Rédigez le message");

        JsonNode scheduled = data(rest(adminJwt, HttpMethod.PATCH, "/api/v1/admin/campaigns/" + id, Map.of(
                "message", "Bonjour", "scheduledAt", java.time.Instant.now().plusSeconds(3600).toString())), 200);
        assertThat(scheduled.path("statusLabel").asText()).isEqualTo("Programmée");
        JsonNode cancelled = data(rest(adminJwt, HttpMethod.POST, "/api/v1/admin/campaigns/" + id + "/cancel", null),
                200);
        assertThat(cancelled.path("statusLabel").asText()).isEqualTo("Annulée");
        assertThat(cancelled.path("history").findValuesAsText("actionLabel"))
                .containsExactly("Créée", "Modifiée", "Programmée", "Annulée");
        assertThat(rest(adminJwt, HttpMethod.DELETE, "/api/v1/admin/campaigns/" + id, null).getResponse()
                .getStatus()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.platform_campaign_events WHERE campaign_id = ? "
                + "AND action = 'DELETED'", Long.class, id)).isEqualTo(1);
    }
}
