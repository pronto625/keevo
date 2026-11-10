package com.keevo.whatsappcloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.invoicing.invoice.application.service.channel.WhatsAppInvoiceChannel;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
import com.keevo.shared.module.ModuleRegistry;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 21.5 — the WhatsApp Business module end to end on PostgreSQL with the production beans and the fake Graph API:
 * connection (token encrypted, number unique, Meta refusals, roles), Meta's signed webhook (routing of one delivery to
 * two companies, idempotency, statuses never going back, template reviews, bad signature, unknown number, subscription
 * check), the inbox (24-hour window, reply, approved template), the templates (draft → submitted at connection,
 * duplicates, delete), the module gate, and the invoice sent by bref_facture from the owner's number.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("WhatsApp Business — account, webhook, inbox, templates, invoice on the full context (21.5)")
class WhatsAppCloudFullContextTest extends FullContextIntegrationTest {

    static final String SECRET = "test-whatsapp-app-secret";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry moduleRegistry;
    @Autowired MetaGraphClient graph;
    @Autowired SecretCipher cipher;
    @Autowired WhatsAppInvoiceChannel invoiceChannel;

    record Member(UUID user, String schema, String jwt) {
    }

    private final List<String> schemas = new ArrayList<>();
    private FakeMetaGraphClient fake;
    private String schema;
    private Member owner;
    private Member employee;

    @BeforeEach
    void setUp() {
        fake = (FakeMetaGraphClient) AopTestUtils.getUltimateTargetObject(graph);
        schema = newTenant();
        owner = owner(schema);
        employee = employee(schema);
    }

    @AfterEach
    void tearDown() {
        for (String s : schemas) {
            jdbc.update("DELETE FROM public.whatsapp_accounts WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                    + "schema_name = ?)", s);
            moduleRegistry.evict(s);
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
        String phone = "+2376" + String.format("%08d", Math.floorMod(user.getLeastSignificantBits(), 100_000_000L));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                + "phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, 'OWNER')", user, phone);
        return new Member(user, tenant, TestAccess.token(jdbc, jwtTokenProvider, user, tenant, "OWNER"));
    }

    private Member employee(String tenant) {
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + tenant + "\".stores ORDER BY created_at LIMIT 1",
                UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + tenant + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", id, store);
        return new Member(id, tenant, TestAccess.token(jdbc, jwtTokenProvider, id, tenant, "EMPLOYEE", "ACTIVE",
                store, false));
    }

    private static String metaId() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L));
    }

    private MvcResult rest(Member m, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + m.jwt());
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

    private static void assertError(MvcResult r, int status, String domainCode) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        assertThat(text).contains("\"domainCode\":\"" + domainCode + "\"");
    }

    /** Connects the owner's number with the fake embedded signup; returns {wabaId, phoneNumberId}. */
    private String[] connect(Member m) throws Exception {
        String waba = metaId();
        String number = metaId();
        JsonNode d = data(rest(m, HttpMethod.POST, "/api/v1/whatsapp/account",
                Map.of("code", "signup-" + UUID.randomUUID(), "wabaId", waba, "phoneNumberId", number)), 200);
        assertThat(d.path("connected").asBoolean()).isTrue();
        return new String[]{waba, number};
    }

    /** The submissions after a connection run in the background: waits (≤ 10 s) for {@code condition}. */
    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("condition reached in time").isLessThan(end);
            Thread.sleep(25);
        }
    }

    /** Waits until the invoice template of {@code tenant} has been submitted (no longer DRAFT). */
    private void awaitInvoiceTemplate(String tenant) throws InterruptedException {
        await(() -> count(tenant, "SELECT count(*) FROM %s.whatsapp_templates WHERE system AND status <> 'DRAFT'") == 1);
    }

    private MvcResult webhook(String json, String signature) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/v1/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(json.getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult signed(String json) throws Exception {
        return webhook(json, MetaWebhookSignature.header(SECRET, json.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> textMessage(String from, String id, String text, long epoch) {
        return Map.of("from", from, "id", id, "timestamp", String.valueOf(epoch), "type", "text",
                "text", Map.of("body", text));
    }

    private static Map<String, Object> change(String number, List<Map<String, Object>> messages,
                                              List<Map<String, Object>> statuses, String contactName,
                                              String contactWaId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("messaging_product", "whatsapp");
        value.put("metadata", Map.of("display_phone_number", "237690000000", "phone_number_id", number));
        if (contactName != null) {
            value.put("contacts", List.of(Map.of("profile", Map.of("name", contactName), "wa_id", contactWaId)));
        }
        if (messages != null) {
            value.put("messages", messages);
        }
        if (statuses != null) {
            value.put("statuses", statuses);
        }
        return Map.of("field", "messages", "value", value);
    }

    private String delivery(List<Map<String, Object>> changes) throws Exception {
        return objectMapper.writeValueAsString(Map.of("object", "whatsapp_business_account",
                "entry", List.of(Map.of("id", "0", "changes", changes))));
    }

    private long count(String tenant, String sql) {
        Long n = jdbc.queryForObject(sql.replace("%s", "\"" + tenant + "\""), Long.class);
        return n == null ? 0 : n;
    }

    // ── account ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("connect: token encrypted at rest (bound to the company), status for every member, bref_facture submitted and not listed")
    void connectEncryptsTheToken() throws Exception {
        JsonNode before = data(rest(employee, HttpMethod.GET, "/api/v1/whatsapp/account", null), 200);
        assertThat(before.path("connected").asBoolean()).isFalse();
        assertThat(before.path("displayPhoneNumber").isNull()).isTrue();

        String code = "signup-" + UUID.randomUUID();
        String waba = metaId();
        String number = metaId();
        JsonNode d = data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/account",
                Map.of("code", code, "wabaId", waba, "phoneNumberId", number)), 200);
        assertThat(d.path("connected").asBoolean()).isTrue();
        assertThat(d.path("displayPhoneNumber").asText()).startsWith("+237 6 90 00");
        assertThat(d.toString()).doesNotContain("fake-token");

        Map<String, Object> row = jdbc.queryForMap("SELECT a.tenant_id, a.access_token_enc FROM "
                + "public.whatsapp_accounts a JOIN public.tenants t ON t.id = a.tenant_id WHERE t.schema_name = ?", schema);
        byte[] enc = (byte[]) row.get("access_token_enc");
        assertThat(new String(enc, StandardCharsets.ISO_8859_1)).doesNotContain("fake-token");
        assertThat(cipher.decrypt(enc, row.get("tenant_id").toString())).isEqualTo("fake-token-" + code);
        assertThatThrownBy(() -> cipher.decrypt(enc, UUID.randomUUID().toString()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(fake.calls("subscribe-app", waba)).hasSize(1);
        awaitInvoiceTemplate(schema);
        assertThat(fake.calls("create-template", waba)).extracting(FakeMetaGraphClient.Call::detail)
                .contains("bref_facture:fr");
        assertThat(count(schema, "SELECT count(*) FROM %s.whatsapp_templates WHERE system AND status = 'PENDING'"))
                .isEqualTo(1);
        assertThat(data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/my-templates", null), 200)).isEmpty();
        assertThat(data(rest(employee, HttpMethod.GET, "/api/v1/whatsapp/account", null), 200)
                .path("connected").asBoolean()).isTrue();
        assertThat(count(schema, "SELECT count(*) FROM %s.audit_log WHERE action = 'WHATSAPP_ACCOUNT_CONNECTED'"))
                .isEqualTo(1);

        // disconnect: row (and token) gone, idempotent
        assertThat(rest(owner, HttpMethod.DELETE, "/api/v1/whatsapp/account", null).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(rest(owner, HttpMethod.DELETE, "/api/v1/whatsapp/account", null).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.whatsapp_accounts a JOIN public.tenants t ON "
                + "t.id = a.tenant_id WHERE t.schema_name = ?", Long.class, schema)).isZero();
        assertThat(count(schema, "SELECT count(*) FROM %s.audit_log WHERE action = 'WHATSAPP_ACCOUNT_DISCONNECTED'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("connect: number of another company 409, Meta refusals 502, wrong ids 400, employee 403")
    void connectRefusals() throws Exception {
        String[] first = connect(owner);
        String other = newTenant();
        Member otherOwner = owner(other);
        assertError(rest(otherOwner, HttpMethod.POST, "/api/v1/whatsapp/account",
                Map.of("code", "c1", "wabaId", metaId(), "phoneNumberId", first[1])), 409,
                "WHATSAPP_NUMBER_ALREADY_CONNECTED");
        assertError(rest(otherOwner, HttpMethod.POST, "/api/v1/whatsapp/account",
                Map.of("code", "invalid-code", "wabaId", metaId(), "phoneNumberId", metaId())), 502,
                "WHATSAPP_CLOUD_META_ERROR");
        assertError(rest(otherOwner, HttpMethod.POST, "/api/v1/whatsapp/account",
                Map.of("code", "c2", "wabaId", metaId(), "phoneNumberId", "0" + metaId())), 502,
                "WHATSAPP_CLOUD_META_ERROR");
        assertThat(rest(otherOwner, HttpMethod.POST, "/api/v1/whatsapp/account",
                Map.of("code", "c3", "wabaId", "abc", "phoneNumberId", metaId())).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(rest(employee, HttpMethod.POST, "/api/v1/whatsapp/account",
                Map.of("code", "c4", "wabaId", metaId(), "phoneNumberId", metaId())).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(rest(employee, HttpMethod.GET, "/api/v1/whatsapp/conversations", null).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.whatsapp_accounts a JOIN public.tenants t ON "
                + "t.id = a.tenant_id WHERE t.schema_name = ?", Long.class, other)).isZero();
    }

    // ── webhook ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("webhook: one signed delivery for two companies lands in each inbox once; replay, bad signature, unknown number")
    void webhookRoutesByNumber() throws Exception {
        String[] a = connect(owner);
        String otherSchema = newTenant();
        Member otherOwner = owner(otherSchema);
        String[] b = connect(otherOwner);
        long now = Instant.now().getEpochSecond();
        String idA = "wamid.A" + UUID.randomUUID();
        String idB = "wamid.B" + UUID.randomUUID();
        String json = delivery(List.of(
                change(a[1], List.of(textMessage("237690112233", idA, "Bonjour, le sac de riz est dispo ?", now)),
                        null, "Awa", "237690112233"),
                change(b[1], List.of(textMessage("237677889900", idB, "Je passe demain", now)), null, "Paul",
                        "237677889900"),
                change(metaId(), List.of(textMessage("237611111111", "wamid.X" + UUID.randomUUID(), "?", now)),
                        null, null, null)));

        assertThat(signed(json).getResponse().getStatus()).isEqualTo(200);
        assertThat(count(schema, "SELECT count(*) FROM %s.whatsapp_messages")).isEqualTo(1);
        assertThat(count(otherSchema, "SELECT count(*) FROM %s.whatsapp_messages")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT wa_message_id FROM \"" + schema + "\".whatsapp_messages", String.class))
                .isEqualTo(idA);

        JsonNode conversations = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/conversations", null), 200);
        assertThat(conversations).hasSize(1);
        JsonNode c = conversations.get(0);
        assertThat(c.path("contactPhone").asText()).isEqualTo("237690112233");
        assertThat(c.path("contactName").asText()).isEqualTo("Awa");
        assertThat(c.path("lastDirection").asText()).isEqualTo("in");
        assertThat(c.path("unreadCount").asInt()).isEqualTo(1);
        assertThat(c.path("replyWindowEndsAt").asText()).isNotBlank();
        assertThat(data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/conversations/unread-count", null), 200)
                .path("count").asLong()).isEqualTo(1);
        // the owners are told (inbox row of the push, after commit, in the background) — once per contact
        await(() -> count(schema, "SELECT count(*) FROM %s.notifications WHERE type = 'WHATSAPP_MESSAGE_RECEIVED'") >= 1);
        assertThat(jdbc.queryForObject("SELECT title || ' | ' || body FROM \"" + schema + "\".notifications WHERE type = "
                + "'WHATSAPP_MESSAGE_RECEIVED' LIMIT 1", String.class))
                .isEqualTo("Nouveau message WhatsApp | Awa : Bonjour, le sac de riz est dispo ?");

        // Meta delivers the same messages again: nothing new
        assertThat(signed(json).getResponse().getStatus()).isEqualTo(200);
        assertThat(count(schema, "SELECT count(*) FROM %s.whatsapp_messages")).isEqualTo(1);
        assertThat(count(schema, "SELECT unread_count FROM %s.whatsapp_conversations")).isEqualTo(1);

        // bad / missing signature: 403, nothing read
        String other = delivery(List.of(change(a[1],
                List.of(textMessage("237690112233", "wamid.Z" + UUID.randomUUID(), "x", now)), null, null, null)));
        assertThat(webhook(other, "sha256=" + "0".repeat(64)).getResponse().getStatus()).isEqualTo(403);
        assertThat(webhook(other, null).getResponse().getStatus()).isEqualTo(403);
        assertThat(count(schema, "SELECT count(*) FROM %s.whatsapp_messages")).isEqualTo(1);

        // opening the conversation marks it read
        JsonNode thread = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/conversations/237690112233/messages",
                null), 200);
        assertThat(thread).hasSize(1);
        assertThat(thread.get(0).path("direction").asText()).isEqualTo("in");
        assertThat(thread.get(0).toString()).doesNotContain("wamid");
        assertThat(data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/conversations/unread-count", null), 200)
                .path("count").asLong()).isZero();
    }

    @Test
    @DisplayName("webhook subscription check: the challenge with the right verify token, 403 otherwise")
    void webhookVerification() throws Exception {
        MvcResult ok = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/whatsapp/webhook")
                .param("hub.mode", "subscribe").param("hub.verify_token", "test-whatsapp-verify-token")
                .param("hub.challenge", "1158201444")).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).isEqualTo("1158201444");
        MvcResult ko = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/whatsapp/webhook")
                .param("hub.mode", "subscribe").param("hub.verify_token", "wrong")
                .param("hub.challenge", "1158201444")).andReturn();
        assertThat(ko.getResponse().getStatus()).isEqualTo(403);
    }

    // ── inbox ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("inbox: reply inside 24 h from the owner's number, statuses never go back, 422 outside the window")
    void replyAndStatuses() throws Exception {
        String[] a = connect(owner);
        long now = Instant.now().getEpochSecond();
        signed(delivery(List.of(change(a[1], List.of(textMessage("237690112233", "wamid.in" + UUID.randomUUID(),
                "Bonjour", now)), null, "Awa", "237690112233"))));

        JsonNode thread = data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/conversations/237690112233/messages",
                Map.of("text", "Oui, il en reste 3")), 200);
        assertThat(thread).hasSize(2);
        assertThat(thread.get(1).path("direction").asText()).isEqualTo("out");
        assertThat(thread.get(1).path("status").asText()).isEqualTo("sent");
        assertThat(fake.calls("send-text", a[1])).extracting(FakeMetaGraphClient.Call::detail)
                .containsExactly("237690112233");
        String outId = jdbc.queryForObject("SELECT wa_message_id FROM \"" + schema + "\".whatsapp_messages WHERE "
                + "direction = 'OUT'", String.class);

        // delivered, then a late « sent », then read
        String deliveredJson = delivery(List.of(change(a[1], null, List.of(Map.of("id", outId, "status", "delivered",
                "timestamp", String.valueOf(now), "recipient_id", "237690112233")), null, null)));
        signed(deliveredJson);
        signed(delivery(List.of(change(a[1], null, List.of(Map.of("id", outId, "status", "sent", "timestamp",
                String.valueOf(now), "recipient_id", "237690112233")), null, null))));
        assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".whatsapp_messages WHERE wa_message_id = ?",
                String.class, outId)).isEqualTo("DELIVERED");
        signed(delivery(List.of(change(a[1], null, List.of(Map.of("id", outId, "status", "read", "timestamp",
                String.valueOf(now), "recipient_id", "237690112233")), null, null))));
        assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".whatsapp_messages WHERE wa_message_id = ?",
                String.class, outId)).isEqualTo("READ");

        // window closed: last inbound 25 h ago; never written: no conversation
        jdbc.update("UPDATE \"" + schema + "\".whatsapp_conversations SET last_inbound_at = now() - interval '25 hours'");
        assertError(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/conversations/237690112233/messages",
                Map.of("text", "Toujours là ?")), 422, "WHATSAPP_REPLY_WINDOW_CLOSED");
        assertError(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/conversations/237655000000/messages",
                Map.of("text", "Bonjour")), 422, "WHATSAPP_REPLY_WINDOW_CLOSED");
        assertThat(fake.calls("send-text", a[1])).hasSize(1);
        assertThat(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/conversations/12/messages", null).getResponse()
                .getStatus()).isEqualTo(400);

        // Meta does not answer a reply: 409 WHATSAPP_SEND_UNCERTAIN (never a retryable 502), nothing stored as sent
        signed(delivery(List.of(change(a[1], List.of(textMessage("237998001122", "wamid.t" + UUID.randomUUID(),
                "Allô", now)), null, null, null))));
        assertError(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/conversations/237998001122/messages",
                Map.of("text", "Oui")), 409, "WHATSAPP_SEND_UNCERTAIN");
        assertError(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/conversations/237999001122/messages",
                Map.of("text", "Oui")), 422, "WHATSAPP_REPLY_WINDOW_CLOSED");
        assertThat(count(schema, "SELECT count(*) FROM %s.whatsapp_messages WHERE contact_phone = '237998001122' "
                + "AND direction = 'OUT'")).isZero();
    }

    @Test
    @DisplayName("thread paging: messages sharing one Meta second are never skipped (keyset created_at + id)")
    void threadKeyset() throws Exception {
        String[] a = connect(owner);
        long now = Instant.now().getEpochSecond();
        List<Map<String, Object>> burst = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            burst.add(textMessage("237690112233", "wamid.k" + i + "-" + UUID.randomUUID(), "m" + i, now));
        }
        assertThat(signed(delivery(List.of(change(a[1], burst, null, "Awa", "237690112233")))).getResponse()
                .getStatus()).isEqualTo(200);
        JsonNode first = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/conversations/237690112233/messages",
                null), 200);
        assertThat(first).hasSize(200);
        JsonNode oldest = first.get(0);
        JsonNode older = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/conversations/237690112233/messages"
                + "?before=" + oldest.path("createdAt").asText() + "&beforeId=" + oldest.path("id").asText(), null),
                200);
        assertThat(older).hasSize(5);
        assertThat(count(schema, "SELECT unread_count FROM %s.whatsapp_conversations")).isZero();
    }

    @Test
    @DisplayName("a number left by a deleted company can be connected by another one")
    void numberOfDeletedCompanyIsFree() throws Exception {
        String[] a = connect(owner);
        jdbc.update("UPDATE public.tenants SET status = 'DELETED' WHERE schema_name = ?", schema);
        try {
            String other = newTenant();
            Member otherOwner = owner(other);
            JsonNode d = data(rest(otherOwner, HttpMethod.POST, "/api/v1/whatsapp/account",
                    Map.of("code", "c-" + UUID.randomUUID(), "wabaId", a[0], "phoneNumberId", a[1])), 200);
            assertThat(d.path("connected").asBoolean()).isTrue();
        } finally {
            jdbc.update("UPDATE public.tenants SET status = 'ACTIVE' WHERE schema_name = ?", schema);
        }
    }

    // ── templates ────────────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> template(String name) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("category", "MARKETING");
        t.put("language", "fr");
        t.put("bodyText", "Bonjour {{1}}, -20 % jusqu'à {{2}} !");
        t.put("bodyExamples", List.of("Awa", "dimanche"));
        t.put("footerText", "Boutique Awa");
        return t;
    }

    @Test
    @DisplayName("templates: draft before connection, submitted at connection, review by webhook, send, duplicate, delete")
    void templateLifecycle() throws Exception {
        JsonNode draft = data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/my-templates", template("promo_rentree")),
                201);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("statusLabel").asText()).isEqualTo("Brouillon");
        assertError(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/my-templates", template("promo_rentree")), 409,
                "WHATSAPP_TEMPLATE_NAME_TAKEN");
        Map<String, Object> bad = template("Promo!");
        assertThat(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/my-templates", bad).getResponse().getStatus())
                .isEqualTo(400);
        Map<String, Object> missingExample = template("promo_b");
        missingExample.put("bodyExamples", List.of("Awa"));
        assertThat(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/my-templates", missingExample).getResponse()
                .getStatus()).isEqualTo(400);

        Map<String, Object> headerVariable = template("promo_c");
        headerVariable.put("headerText", "Promo {{1}}");
        assertThat(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/my-templates", headerVariable).getResponse()
                .getStatus()).isEqualTo(400);

        String[] a = connect(owner);
        await(() -> count(schema, "SELECT count(*) FROM %s.whatsapp_templates WHERE status = 'PENDING'") == 2);
        JsonNode listed = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/my-templates", null), 200);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("status").asText()).isEqualTo("PENDING");
        String templateId = listed.get(0).path("id").asText();

        // not approved yet: refused, nothing sent
        long now = Instant.now().getEpochSecond();
        assertError(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/conversations/237690112233/template",
                Map.of("templateId", templateId, "variables", List.of("Awa", "dimanche"))), 409,
                "WHATSAPP_TEMPLATE_NOT_APPROVED");

        // Meta approves (webhook addressed to the WABA)
        String review = objectMapper.writeValueAsString(Map.of("object", "whatsapp_business_account", "entry",
                List.of(Map.of("id", a[0], "time", now, "changes", List.of(Map.of("field",
                        "message_template_status_update", "value", Map.of("event", "APPROVED",
                                "message_template_id", 1234567, "message_template_name", "promo_rentree",
                                "message_template_language", "fr", "reason", "NONE")))))));
        assertThat(signed(review).getResponse().getStatus()).isEqualTo(200);
        JsonNode approved = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/my-templates", null), 200).get(0);
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("statusLabel").asText()).isEqualTo("Approuvé");

        assertThat(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/conversations/237690112233/template",
                Map.of("templateId", templateId, "variables", List.of("Awa"))).getResponse().getStatus())
                .isEqualTo(400);
        JsonNode thread = data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/conversations/237690112233/template",
                Map.of("templateId", templateId, "variables", List.of("Awa", "dimanche"))), 200);
        assertThat(thread).hasSize(1);
        assertThat(thread.get(0).path("body").asText()).isEqualTo("Bonjour Awa, -20 % jusqu'à dimanche !");
        assertThat(fake.calls("send-template", a[1])).extracting(FakeMetaGraphClient.Call::detail)
                .containsExactly("237690112233:promo_rentree:fr:1");

        // a REJECTED review with its reason
        String rejected = review.replace("\"APPROVED\"", "\"REJECTED\"").replace("\"NONE\"", "\"INVALID_FORMAT\"");
        signed(rejected);
        JsonNode r = data(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/my-templates", null), 200).get(0);
        assertThat(r.path("status").asText()).isEqualTo("REJECTED");
        assertThat(r.path("rejected_reason").asText()).isEqualTo("INVALID_FORMAT");

        assertThat(rest(owner, HttpMethod.DELETE, "/api/v1/whatsapp/my-templates/" + templateId, null).getResponse()
                .getStatus()).isEqualTo(204);
        assertThat(fake.calls("delete-template", a[0])).extracting(FakeMetaGraphClient.Call::detail)
                .containsExactly("promo_rentree");
        assertError(rest(owner, HttpMethod.DELETE, "/api/v1/whatsapp/my-templates/" + templateId, null), 404,
                "WHATSAPP_TEMPLATE_NOT_FOUND");

        // media header sample: uploaded to Meta, handle returned
        MvcResult media = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/whatsapp/my-templates/media")
                .file(new MockMultipartFile("file", "promo.png", "image/png", new byte[]{1, 2, 3}))
                .header("Authorization", "Bearer " + owner.jwt())).andReturn();
        assertThat(data(media, 200).path("headerHandle").asText()).startsWith("fake-handle-");
    }

    // ── module gate ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FREE plan: gated endpoints 403 MODULE_NOT_ENTITLED, the webhook ignores the company, disconnect still allowed")
    void moduleGate() throws Exception {
        String[] a = connect(owner);
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES ((SELECT id FROM "
                + "public.tenants WHERE schema_name = ?), 'whatsapp-cloud', 'DISABLED', 'FREE') ON CONFLICT (tenant_id, "
                + "module_id) DO UPDATE SET status = 'DISABLED'", schema);
        moduleRegistry.evict(schema);
        try {
            assertError(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/conversations", null), 403, "MODULE_NOT_ENTITLED");
            assertError(rest(owner, HttpMethod.GET, "/api/v1/whatsapp/account", null), 403, "MODULE_NOT_ENTITLED");
            assertThat(signed(delivery(List.of(change(a[1], List.of(textMessage("237690112233",
                    "wamid.G" + UUID.randomUUID(), "Bonjour", Instant.now().getEpochSecond())), null, null, null))))
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(count(schema, "SELECT count(*) FROM %s.whatsapp_messages")).isZero();
            assertThat(rest(owner, HttpMethod.DELETE, "/api/v1/whatsapp/account", null).getResponse().getStatus())
                    .isEqualTo(204);
        } finally {
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                    + "schema_name = ?) AND module_id = 'whatsapp-cloud'", schema);
            moduleRegistry.evict(schema);
        }
    }

    // ── invoice by bref_facture ──────────────────────────────────────────────────────────────────────────

    /** Records platform sends (must stay empty when the company's number sends). */
    static final class PlatformWhatsApp implements WhatsAppSender {
        final List<String> sent = new ArrayList<>();
        volatile boolean configured = true;

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public void sendReport(String phoneNumber, String reportText) {
            sent.add(phoneNumber);
        }
    }

    @Test
    @DisplayName("invoice: bref_facture approved → PDF + template from the owner's number, platform not used; not approved → platform text")
    void invoiceFromTheOwnersNumber() throws Exception {
        Object target = AopTestUtils.getUltimateTargetObject(invoiceChannel);
        Object original = ReflectionTestUtils.getField(target, "whatsApp");
        PlatformWhatsApp platform = new PlatformWhatsApp();
        ReflectionTestUtils.setField(target, "whatsApp", platform);
        try {
            String[] a = connect(owner);
            String first = issuedInvoice();
            // template still PENDING: the platform text
            assertThat(sendInvoice(first).getResponse().getStatus()).isEqualTo(200);
            assertThat(platform.sent).containsExactly("+237690112233");
            assertThat(fake.calls("send-template", a[1])).isEmpty();

            awaitInvoiceTemplate(schema);
            jdbc.update("UPDATE \"" + schema + "\".whatsapp_templates SET status = 'APPROVED' WHERE name = 'bref_facture'");
            String second = issuedInvoice();
            MvcResult r = sendInvoice(second);
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(platform.sent).hasSize(1);
            assertThat(fake.calls("upload-media", a[1])).extracting(FakeMetaGraphClient.Call::detail)
                    .hasSize(1).allMatch(d -> d.startsWith("application/pdf:"));
            assertThat(fake.calls("send-template", a[1])).extracting(FakeMetaGraphClient.Call::detail)
                    .containsExactly("237690112233:bref_facture:fr:2");
            assertThat(jdbc.queryForObject("SELECT body FROM \"" + schema + "\".whatsapp_messages WHERE type = "
                    + "'document'", String.class)).startsWith("Bonjour Société Ndolo, voici votre facture FAC-");
            assertThat(jdbc.queryForObject("SELECT send_count FROM \"" + schema + "\".invoices WHERE id = ?::uuid",
                    Integer.class, second)).isEqualTo(1);

            // Meta refuses (number not on WhatsApp): the platform text with the link takes over
            String refused = issuedInvoice("+237999112233");
            assertThat(sendInvoice(refused).getResponse().getStatus()).isEqualTo(200);
            assertThat(platform.sent).containsExactly("+237690112233", "+237999112233");

            // Meta does not answer: maybe delivered — 409 INVOICE_SEND_UNCERTAIN, never the platform text too
            String uncertain = issuedInvoice("+237998112233");
            assertError(sendInvoice(uncertain), 409, "INVOICE_SEND_UNCERTAIN");
            assertThat(platform.sent).hasSize(2);

            // only the company's number can send (no platform provider): still available and sent from it
            platform.configured = false;
            String own = issuedInvoice("+237690112233");
            assertThat(sendInvoice(own).getResponse().getStatus()).isEqualTo(200);
            assertThat(fake.calls("send-template", a[1])).extracting(FakeMetaGraphClient.Call::detail)
                    .filteredOn(d -> d.startsWith("237690112233:bref_facture")).hasSize(2);

            // number moved to another WABA: the templates go back to review, invoices use the platform again
            platform.configured = true;
            JsonNode moved = data(rest(owner, HttpMethod.POST, "/api/v1/whatsapp/account", Map.of("code",
                    "signup-" + UUID.randomUUID(), "wabaId", metaId(), "phoneNumberId", a[1])), 200);
            assertThat(moved.path("connected").asBoolean()).isTrue();
            awaitInvoiceTemplate(schema);
            assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".whatsapp_templates WHERE name = "
                    + "'bref_facture'", String.class)).isEqualTo("PENDING");
            String afterMove = issuedInvoice("+237690112233");
            assertThat(sendInvoice(afterMove).getResponse().getStatus()).isEqualTo(200);
            assertThat(platform.sent).hasSize(3);
        } finally {
            ReflectionTestUtils.setField(target, "whatsApp", original);
        }
    }

    private String issuedInvoice() throws Exception {
        return issuedInvoice("+237690112233");
    }

    private String issuedInvoice(String clientPhone) throws Exception {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", "Société Ndolo");
        client.put("phone", clientPhone);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 2);
        line.put("unitPrice", 11_925);
        line.put("vatTreatment", "STANDARD");
        String id = data(rest(owner, HttpMethod.POST, "/api/v1/invoices", Map.of("client", client, "lines",
                List.of(line))), 201).path("id").asText();
        data(rest(owner, HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        return id;
    }

    private MvcResult sendInvoice(String id) throws Exception {
        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/invoices/" + id + "/send")
                .header("Authorization", "Bearer " + owner.jwt())
                .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "keevo.ad2s.cm")).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return r;
    }
}
