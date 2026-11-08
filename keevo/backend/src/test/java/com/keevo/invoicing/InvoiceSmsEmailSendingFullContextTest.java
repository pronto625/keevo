package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.invoicing.invoice.application.service.channel.EmailInvoiceChannel;
import com.keevo.invoicing.invoice.application.service.channel.SmsInvoiceChannel;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.capability.notification.SmsSender;
import com.keevo.shared.capability.notification.TenantMessaging;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
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

/**
 * Story 20.8 (FUS-FR22) — sending a document by SMS and email on the real HTTP chain (production security, real JWTs,
 * entitlement aspect, PostgreSQL, the real 19.8 quota counters and the real {@code MeteredTenantMessaging}): fake SMS /
 * email senders are swapped in (never a real provider). Every row of the spec's I/O matrix, plus the offline
 * {@code SEND_INVOICE} replayed with one operationId.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Invoice sending by SMS / email — full context (Story 20.8)")
class InvoiceSmsEmailSendingFullContextTest extends FullContextIntegrationTest {

    private static final String PLAN = "TEST_SEND_FC";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantMessaging tenantMessaging;
    @Autowired SmsInvoiceChannel smsChannel;
    @Autowired EmailInvoiceChannel emailChannel;
    @Autowired PlanModuleRowSync rowSync;
    @Autowired PlanCatalogStore catalogStore;
    @Autowired ModuleRegistry registry;
    @Autowired com.keevo.invoicing.invoice.application.service.InvoiceSendOutbox outbox;
    @Autowired com.keevo.invoicing.invoice.adapter.in.scheduling.InvoiceSendOutboxJob outboxJob;

    /** Captures the SMS that would leave; {@code fail} = every provider failed. */
    static final class FakeSms implements SmsSender {
        final List<SmsMessage> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile boolean enabled = true;
        volatile boolean fail;
        volatile boolean uncertain;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public DeliveryResult send(SmsMessage message) {
            if (fail) {
                return DeliveryResult.notDelivered();
            }
            if (uncertain) {
                return DeliveryResult.uncertain("fake");
            }
            sent.add(message);
            return DeliveryResult.deliveredBy("fake");
        }
    }

    /** Captures the emails that would leave; {@code fail} = every provider failed. */
    static final class FakeEmail implements EmailSender {
        final List<EmailMessage> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile boolean enabled = true;
        volatile boolean fail;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public DeliveryResult send(EmailMessage message) {
            if (fail) {
                return DeliveryResult.notDelivered();
            }
            sent.add(message);
            return DeliveryResult.deliveredBy("fake");
        }
    }

    private final UUID ownerId = UUID.randomUUID();
    private final FakeSms sms = new FakeSms();
    private final FakeEmail email = new FakeEmail();
    private final Map<Object[], Object> originals = new LinkedHashMap<>();
    private final String month = YearMonth.now(ZoneId.of("Africa/Douala")).toString();
    private final String deviceId = UUID.randomUUID().toString();
    private Instant clientClock = Instant.now().minusSeconds(600);
    private String schema;
    private UUID tenantId;
    private String ownerToken;
    private String year;

    @BeforeEach
    void setUp() throws Exception {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        ownerToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema,
                "OWNER");
        year = String.valueOf(LocalDate.now(ZoneId.of("Africa/Douala")).getYear());
        swap(AopTestUtils.getUltimateTargetObject(tenantMessaging), "smsSender", sms);
        swap(AopTestUtils.getUltimateTargetObject(tenantMessaging), "emailSender", email);
        swap(AopTestUtils.getUltimateTargetObject(smsChannel), "smsSender", sms);
        swap(AopTestUtils.getUltimateTargetObject(emailChannel), "emailSender", email);
        // the poller has no request: the configured public base (KEEVO_PUBLIC_BASE_URL) is used
        swap(AopTestUtils.getUltimateTargetObject(outbox), "publicBaseUrl", "https://keevo.test");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("legalName", "Boutique Awa SARL");
        s.put("address", "Rue Joss, Akwa, Douala");
        s.put("phone", "+237699000000");
        s.put("vatRegistered", false);
        s.put("vatRate", 19.25);
        s.put("pricesIncludeVat", true);
        data(call(HttpMethod.PUT, "/api/v1/invoicing/settings", s), 200);
    }

    @AfterEach
    void tearDown() {
        originals.forEach((key, value) -> ReflectionTestUtils.setField(key[0], (String) key[1], value));
        jdbc.update("DELETE FROM public.usage_counters WHERE tenant_id = ?", tenantId);
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

    private void swap(Object target, String field, Object fake) {
        originals.put(new Object[]{target, field}, ReflectionTestUtils.getField(target, field));
        ReflectionTestUtils.setField(target, field, fake);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private MvcResult call(HttpMethod method, String path, Object body) throws Exception {
        return callAs(ownerToken, method, path, body);
    }

    private MvcResult callAs(String token, HttpMethod method, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path)
                .header("Authorization", "Bearer " + token);
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

    private void assertError(MvcResult r, int status, String domainCode, String message) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        JsonNode json = objectMapper.readTree(body(r));
        assertThat(json.path("domainCode").asText()).as(body(r)).isEqualTo(domainCode);
        if (message != null) {
            assertThat(json.path("error").asText()).isEqualTo(message);
        }
    }

    /** A document for « Société Ndolo » with the given phone / email (null = none). */
    private String create(String kind, String phone, String mail) throws Exception {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", "Société Ndolo");
        client.put("phone", phone);
        client.put("email", mail);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 2);
        line.put("unitPrice", 11_925);
        line.put("vatTreatment", "STANDARD");
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("client", client);
        draft.put("lines", List.of(line));
        if (kind != null) {
            draft.put("kind", kind);
        }
        return data(call(HttpMethod.POST, "/api/v1/invoices", draft), 201).path("id").asText();
    }

    private String issued(String phone, String mail) throws Exception {
        String id = create(null, phone, mail);
        data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        return id;
    }

    private MvcResult send(String id, Map<String, Object> body) throws Exception {
        return call(HttpMethod.POST, "/api/v1/invoices/" + id + "/send", body);
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("SELECT last_sent_at, last_sent_to, last_sent_channel, send_count, updated_at FROM \""
                + schema + "\".invoices WHERE id = ?::uuid", id);
    }

    private long used(String metric) {
        List<Long> rows = jdbc.queryForList("SELECT used FROM public.usage_counters WHERE tenant_id = ? "
                + "AND metric = ? AND period_key = ?", Long.class, tenantId, metric, month);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private List<Map<String, Object>> sentAudits() {
        return jdbc.queryForList("SELECT action, entity_label AS label, value_after AS after FROM \""
                + schema + "\".audit_log WHERE action IN ('INVOICE_SENT', 'QUOTE_SENT') ORDER BY occurred_at");
    }

    private JsonNode after(Map<String, Object> audit) {
        try {
            return objectMapper.readTree((String) audit.get("after"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String linkPath(String message) {
        int i = message.indexOf("/api/v1/invoices/shared?token=");
        assertThat(i).as(message).isPositive();
        String rest = message.substring(i);
        int end = 0;
        while (end < rest.length() && !Character.isWhitespace(rest.charAt(end)) && rest.charAt(end) != '"') {
            end++;
        }
        return rest.substring(0, end);
    }

    /** The tenant moves to a paid test plan whose SMS quota is {@code smsPerMonth} per month. */
    private void smsQuota(int smsPerMonth) {
        jdbc.update("INSERT INTO public.plans (code, label, sort_order, visible, kind) VALUES (?, 'Offre test', 98, "
                + "false, 'PAID') ON CONFLICT (code) DO NOTHING", PLAN);
        jdbc.update("INSERT INTO public.plan_profiles (plan_type) VALUES (?) ON CONFLICT (plan_type) DO NOTHING",
                PLAN);
        jdbc.update("INSERT INTO public.plan_quotas (plan_type, metric, period, quota_limit) VALUES (?, 'SMS', "
                + "'MONTH', ?)", PLAN, smsPerMonth);
        catalogStore.invalidate();
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = ?, status = 'ACTIVE', "
                + "expires_at = now() + interval '30 days'", PLAN);
        jdbc.update("UPDATE public.tenants SET plan_type = ? WHERE id = ?", PLAN, tenantId);
        rowSync.sync(tenantId, schema);
        registry.evict(schema);
    }

    // ── matrix ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SMS: client phone normalised, Bref text + public link in GSM-7 within 2 segments, recorded with its "
            + "channel, SMS counted 1 after delivery, audited « SMS », response channel label")
    void smsSuccess() throws Exception {
        String id = issued("677 00 01 11", null);
        assertThat(used("SMS")).isZero();

        JsonNode sent = data(send(id, Map.of("channel", "SMS")), 200);

        assertThat(sms.sent).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo("+237677000111");
            assertThat(m.text()).startsWith("Facture FAC-" + year + "-000001 - Boutique Awa SARL\n"
                    + "Merci pour votre achat !\nVotre facture : http");
            assertThat(m.text().length()).isLessThanOrEqualTo(306);
            assertThat(m.text()).matches("[\\n\\x20-\\x7E]+");
        });
        // the link opens the PDF
        MvcResult pdf = mockMvc.perform(MockMvcRequestBuilders.get(linkPath(sms.sent.get(0).text()))).andReturn();
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");

        assertThat(sent.path("lastSentChannel").asText()).isEqualTo("SMS");
        assertThat(sent.path("lastSentChannelLabel").asText()).isEqualTo("SMS");
        assertThat(sent.path("lastSentTo").asText()).isEqualTo("+237 6•• ••• 111");
        assertThat(sent.path("sendCount").asInt()).isEqualTo(1);
        Map<String, Object> row = row(id);
        assertThat(row.get("last_sent_channel")).isEqualTo("SMS");
        assertThat(row.get("last_sent_to")).isEqualTo("+237677000111");
        assertThat(used("SMS")).isEqualTo(1);
        assertThat(used("EMAILS")).isZero();
        assertThat(sentAudits()).singleElement().satisfies(a -> {
            assertThat(a.get("action")).isEqualTo("INVOICE_SENT");
            assertThat(after(a).path("channel").asText()).isEqualTo("SMS");
            assertThat(after(a).path("sentTo").asText()).isEqualTo("+237 6•• ••• 111");
            assertThat((String) a.get("after")).doesNotContain("677000111");
        });
    }

    @Test
    @DisplayName("Email of a quote: Bref subject / text / HTML with the link, PDF attached, shop as sender name, "
            + "EMAILS counted 1, audited « Devis envoyé » channel EMAIL, masked address")
    void emailQuote() throws Exception {
        String id = create("QUOTE", null, "Compta@Ndolo.CM");
        JsonNode quote = data(call(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200);
        String reference = quote.path("quoteReference").asText();

        JsonNode sent = data(send(id, Map.of("channel", "email")), 200);

        assertThat(email.sent).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo("Compta@ndolo.cm");
            assertThat(m.subject()).isEqualTo("Votre devis " + reference + " — Boutique Awa SARL");
            assertThat(m.text()).startsWith("Bonjour Société Ndolo,\n\nVeuillez trouver ci-joint votre devis "
                    + reference + " de Boutique Awa SARL.\n\nMontant : 23 850 XAF\n\nConsultez votre devis en ligne : ")
                    .endsWith("Merci de votre confiance.");
            assertThat(m.html()).contains("Société Ndolo", "N° DEVIS", reference, "En attente de validation",
                    "23 850 XAF", "Rue Joss, Akwa, Douala", "Voir le devis en ligne");
            assertThat(m.fromName()).isEqualTo("Boutique Awa SARL");
            assertThat(m.attachments()).singleElement().satisfies(a -> {
                assertThat(a.contentType()).isEqualTo("application/pdf");
                assertThat(a.filename()).endsWith(".pdf");
                assertThat(new String(a.content(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
            });
            assertThat(m.text() + m.html()).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-");
        });
        MvcResult pdf = mockMvc.perform(MockMvcRequestBuilders.get(linkPath(email.sent.get(0).text()))).andReturn();
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);

        assertThat(sent.path("lastSentChannel").asText()).isEqualTo("EMAIL");
        assertThat(sent.path("lastSentChannelLabel").asText()).isEqualTo("E-mail");
        assertThat(sent.path("lastSentTo").asText()).isEqualTo("C***@ndolo.cm");
        assertThat(used("EMAILS")).isEqualTo(1);
        assertThat(used("SMS")).isZero();
        assertThat(sentAudits()).singleElement().satisfies(a -> {
            assertThat(a.get("action")).isEqualTo("QUOTE_SENT");
            assertThat(a.get("label")).isEqualTo(reference);
            assertThat(after(a).path("channel").asText()).isEqualTo("EMAIL");
            assertThat(after(a).path("sentTo").asText()).isEqualTo("C***@ndolo.cm");
        });
    }

    @Test
    @DisplayName("Overrides: SMS to the typed number, email to the typed address (not the client's)")
    void overrides() throws Exception {
        String id = issued("+237677000111", "compta@ndolo.cm");
        data(send(id, Map.of("channel", "SMS", "phone", "690 00 00 02")), 200);
        assertThat(sms.sent.get(0).to()).isEqualTo("+237690000002");
        data(send(id, Map.of("channel", "EMAIL", "email", "patron@ndolo.cm")), 200);
        assertThat(email.sent.get(0).to()).isEqualTo("patron@ndolo.cm");
        assertThat(row(id).get("send_count")).isEqualTo(2);
        assertThat(row(id).get("last_sent_channel")).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("Missing / invalid recipient → 422 French per channel, nothing sent, nothing counted, invoice unchanged")
    void recipients() throws Exception {
        String id = issued(null, null);
        Map<String, Object> before = row(id);
        assertError(send(id, Map.of("channel", "SMS")), 422, "INVOICE_SMS_PHONE_REQUIRED",
                "Indiquez le numéro de téléphone du client pour l'envoi par SMS");
        assertError(send(id, Map.of("channel", "SMS", "phone", "6990")), 422, "INVOICE_SMS_PHONE_INVALID",
                "Le numéro de téléphone du client n'est pas valide pour l'envoi par SMS");
        assertError(send(id, Map.of("channel", "EMAIL")), 422, "INVOICE_EMAIL_REQUIRED",
                "Indiquez l'adresse e-mail du client");
        assertError(send(id, Map.of("channel", "EMAIL", "email", "pas-une-adresse")), 422, "INVOICE_EMAIL_INVALID",
                "L'adresse e-mail du client n'est pas valide");
        assertError(send(id, Map.of("channel", "FAX")), 422, "VALIDATION_ERROR",
                "Canal d'envoi inconnu : choisissez WhatsApp, SMS ou e-mail");
        assertThat(sms.sent).isEmpty();
        assertThat(email.sent).isEmpty();
        assertThat(row(id)).isEqualTo(before);
        assertThat(used("SMS")).isZero();
        assertThat(used("EMAILS")).isZero();
        assertThat(sentAudits()).isEmpty();
    }

    @Test
    @DisplayName("Provider failure → 502 French, invoice unchanged (send_count, updated_at), no unit counted, no audit")
    void providerFailure() throws Exception {
        String id = issued("677000111", "compta@ndolo.cm");
        Map<String, Object> before = row(id);
        sms.fail = true;
        email.fail = true;
        assertError(send(id, Map.of("channel", "SMS")), 502, "SMS_DELIVERY_FAILED",
                "Le SMS n'a pas pu être envoyé. Réessayez dans quelques instants.");
        assertError(send(id, Map.of("channel", "EMAIL")), 502, "EMAIL_DELIVERY_FAILED",
                "L'e-mail n'a pas pu être envoyé. Réessayez dans quelques instants.");
        assertThat(row(id)).isEqualTo(before);
        assertThat(used("SMS")).isZero();
        assertThat(used("EMAILS")).isZero();
        assertThat(sentAudits()).isEmpty();
    }

    @Test
    @DisplayName("Channel without provider → 503 French; quota exhausted → 403 QUOTA_EXCEEDED before sending")
    void unavailableAndQuota() throws Exception {
        String id = issued("677000111", "compta@ndolo.cm");
        sms.enabled = false;
        email.enabled = false;
        assertError(send(id, Map.of("channel", "SMS")), 503, "SMS_UNAVAILABLE",
                "L'envoi par SMS n'est pas disponible pour le moment");
        assertError(send(id, Map.of("channel", "EMAIL")), 503, "EMAIL_UNAVAILABLE",
                "L'envoi par e-mail n'est pas disponible pour le moment");
        sms.enabled = true;

        smsQuota(1);
        data(send(id, Map.of("channel", "SMS")), 200);
        assertThat(used("SMS")).isEqualTo(1);
        Map<String, Object> before = row(id);
        assertError(send(id, Map.of("channel", "SMS")), 403, "QUOTA_EXCEEDED", null);
        assertThat(sms.sent).hasSize(1);
        assertThat(used("SMS")).isEqualTo(1);
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    @DisplayName("Legacy body (no channel) stays WhatsApp; a draft is not sendable by SMS (409)")
    void legacyAndNotSendable() throws Exception {
        String draft = create(null, "677000111", null);
        assertError(send(draft, Map.of("channel", "SMS")), 409, "INVOICE_INVALID_TRANSITION", null);
        String id = issued("677000111", null);
        // the context's WhatsApp provider is the NoOp one: the 14.2 answer, untouched
        assertError(send(id, Map.of("phone", "677000111")), 503, "WHATSAPP_UNAVAILABLE", null);
        assertError(send(id, null), 503, "WHATSAPP_UNAVAILABLE", null);
        assertThat(sms.sent).isEmpty();
    }

    @Test
    @DisplayName("REST: an email address over 20 characters is stored in full; an uncertain SMS → 409 French, not "
            + "counted, nothing recorded, logged UNKNOWN")
    void longEmailAndUncertain() throws Exception {
        String id = issued("677000111", null);
        String longEmail = "tres.longue.adresse.comptabilite@ndolo.cm";
        data(send(id, Map.of("channel", "EMAIL", "email", longEmail)), 200);
        assertThat(row(id).get("last_sent_to")).isEqualTo(longEmail);

        Map<String, Object> before = row(id);
        sms.uncertain = true;
        assertError(send(id, Map.of("channel", "SMS")), 409, "INVOICE_SEND_UNCERTAIN",
                "Envoi incertain : vérifiez auprès du client avant de renvoyer");
        assertThat(row(id)).isEqualTo(before);
        assertThat(used("SMS")).isZero();
        assertThat(jdbc.queryForList("SELECT status FROM \"" + schema + "\".invoice_send_requests WHERE invoice_id = "
                + "?::uuid ORDER BY created_at", String.class, id)).containsExactly("SENT", "UNKNOWN");
    }

    @Test
    @DisplayName("REST channels[] (Bref): each channel independently, one result per channel with French messages")
    void restChannels() throws Exception {
        String id = issued("677000111", null);
        JsonNode result = data(send(id, Map.of("channels", List.of("sms", "email"))), 200);
        assertThat(result.path("results")).hasSize(2);
        assertThat(result.path("results").get(0).path("channel").asText()).isEqualTo("SMS");
        assertThat(result.path("results").get(0).path("status").asText()).isEqualTo("SENT");
        assertThat(result.path("results").get(1).path("channel").asText()).isEqualTo("EMAIL");
        assertThat(result.path("results").get(1).path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("results").get(1).path("statusLabel").asText()).isEqualTo("Échec de l'envoi");
        assertThat(result.path("results").get(1).path("error").asText()).isEqualTo("Indiquez l'adresse e-mail du client");
        assertThat(result.path("invoice").path("lastSentChannel").asText()).isEqualTo("SMS");
        assertThat(sms.sent).hasSize(1);
        assertError(send(id, Map.of("channel", "SMS", "email", "a@b.cm")), 422, "VALIDATION_ERROR",
                "L'adresse e-mail ne sert qu'à l'envoi par e-mail");
        assertError(send(id, Map.of("channel", "EMAIL", "phone", "690000001")), 422, "VALIDATION_ERROR",
                "Le numéro de téléphone ne sert pas à l'envoi par e-mail");
    }

    // ── sync (outbox) ────────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> sendOp(String invoiceId, Map<String, Object> payload) {
        clientClock = clientClock.plusSeconds(1);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", "SEND_INVOICE");
        op.put("entityId", invoiceId);
        op.put("payload", payload);
        op.put("clientTimestamp", clientClock.toString());
        return op;
    }

    private JsonNode push(String token, Map<String, Object> op) throws Exception {
        JsonNode data = data(callAs(token, HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", deviceId, "operations", List.of(op))), 200);
        return data.path("results").get(0);
    }

    private List<Map<String, Object>> requests(String inSchema, String operationId) {
        return jdbc.queryForList("SELECT channel, status, attempts, last_error_code, next_attempt_at FROM \""
                + inSchema + "\".invoice_send_requests WHERE operation_id = ? ORDER BY channel", operationId);
    }

    private void awaitStatus(String operationId, String status) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(requests(schema, operationId)).allSatisfy(r -> assertThat(r.get("status")).isEqualTo(status)));
    }

    private static Map<String, Object> payload(String id, Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("invoiceId", id);
        for (int i = 0; i < kv.length; i += 2) {
            p.put((String) kv[i], kv[i + 1]);
        }
        return p;
    }

    @Test
    @DisplayName("Sync SEND_INVOICE: APPLIED = queued (nothing sent in the push), sent after commit with an absolute "
            + "link; pushed again → DUPLICATE, still one SMS, counted once; the pull shows SENT")
    void syncQueuedOncePerOperation() throws Exception {
        String id = issued("677000111", null);
        Map<String, Object> op = sendOp(id, payload(id, "channel", "SMS"));

        JsonNode first = push(ownerToken, op);
        assertThat(first.path("status").asText()).as(first.toString()).isEqualTo("APPLIED");
        assertThat(first.path("serverEntityId").asText()).isEqualTo(id);
        awaitStatus((String) op.get("operationId"), "SENT");
        assertThat(push(ownerToken, op).path("status").asText()).isEqualTo("DUPLICATE");
        outboxJob.run(); // nothing due: the poller sends nothing more

        assertThat(sms.sent).hasSize(1);
        assertThat(sms.sent.get(0).text()).contains("Votre facture : https://keevo.test/api/v1/invoices/shared?token=");
        assertThat(requests(schema, (String) op.get("operationId"))).singleElement()
                .satisfies(r -> assertThat(r.get("attempts")).isEqualTo(1));
        assertThat(used("SMS")).isEqualTo(1);
        assertThat(row(id).get("send_count")).isEqualTo(1);
        assertThat(sentAudits()).hasSize(1);
        JsonNode invoice = data(call(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200);
        assertThat(invoice.path("lastSendStatus").asText()).isEqualTo("SENT");
        assertThat(invoice.path("lastSentChannel").asText()).isEqualTo("SMS");
        // the PDF link opens
        MvcResult pdf = mockMvc.perform(MockMvcRequestBuilders.get(
                linkPath(sms.sent.get(0).text()).replace("https://keevo.test", ""))).andReturn();
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Sync channels[sms, email]: one request per channel, each sent once")
    void syncChannels() throws Exception {
        String id = issued("677000111", "compta@ndolo.cm");
        Map<String, Object> op = sendOp(id, payload(id, "channels", List.of("sms", "email")));
        assertThat(push(ownerToken, op).path("status").asText()).isEqualTo("APPLIED");
        awaitStatus((String) op.get("operationId"), "SENT");
        assertThat(requests(schema, (String) op.get("operationId"))).extracting(r -> r.get("channel"))
                .containsExactly("EMAIL", "SMS");
        assertThat(sms.sent).hasSize(1);
        assertThat(email.sent).hasSize(1);
        assertThat(row(id).get("send_count")).isEqualTo(2);
    }

    @Test
    @DisplayName("Sync provider failure: back to PENDING with backoff (pull shows PENDING), the poller sends it once "
            + "due; push-time refusal → REJECTED, nothing queued")
    void syncRetryWithBackoff() throws Exception {
        String id = issued("677000111", null);
        sms.fail = true;
        Map<String, Object> op = sendOp(id, payload(id, "channel", "SMS"));
        String opId = (String) op.get("operationId");
        assertThat(push(ownerToken, op).path("status").asText()).isEqualTo("APPLIED");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(requests(schema, opId)).singleElement().satisfies(r -> {
                    assertThat(r.get("status")).isEqualTo("PENDING");
                    assertThat(r.get("attempts")).isEqualTo(1);
                    assertThat(r.get("last_error_code")).isEqualTo("SMS_DELIVERY_FAILED");
                }));
        java.sql.Timestamp next = (java.sql.Timestamp) requests(schema, opId).get(0).get("next_attempt_at");
        assertThat(next.toInstant()).isAfter(Instant.now().plusSeconds(15)).isBefore(Instant.now().plusSeconds(40));
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200).path("lastSendStatus").asText())
                .isEqualTo("PENDING");
        outboxJob.run(); // not due yet
        assertThat(requests(schema, opId).get(0).get("attempts")).isEqualTo(1);

        sms.fail = false;
        jdbc.update("UPDATE \"" + schema + "\".invoice_send_requests SET next_attempt_at = now() - interval '1 second' "
                + "WHERE operation_id = ?", opId);
        outboxJob.run();
        assertThat(requests(schema, opId)).singleElement().satisfies(r -> {
            assertThat(r.get("status")).isEqualTo("SENT");
            assertThat(r.get("attempts")).isEqualTo(2);
        });
        assertThat(sms.sent).hasSize(1);

        // refused at push time: nothing queued
        String noPhone = issued(null, null);
        Map<String, Object> refused = sendOp(noPhone, payload(noPhone, "channel", "SMS"));
        JsonNode r = push(ownerToken, refused);
        assertThat(r.path("status").asText()).isEqualTo("REJECTED");
        assertThat(r.path("reason").asText()).isEqualTo("INVOICE_SMS_PHONE_REQUIRED");
        assertThat(requests(schema, (String) refused.get("operationId"))).isEmpty();
    }

    @Test
    @DisplayName("Sync uncertain → UNKNOWN, never retried, nothing counted; the pull shows « Envoi incertain »; quota "
            + "exhausted and missing public base → FAILED with their codes")
    void syncUnknownQuotaAndBase() throws Exception {
        String id = issued("677000111", null);
        sms.uncertain = true;
        Map<String, Object> op = sendOp(id, payload(id, "channel", "SMS"));
        push(ownerToken, op);
        awaitStatus((String) op.get("operationId"), "UNKNOWN");
        sms.uncertain = false;
        jdbc.update("UPDATE \"" + schema + "\".invoice_send_requests SET next_attempt_at = now() - interval '1 second'");
        outboxJob.run();
        assertThat(sms.sent).isEmpty();
        assertThat(used("SMS")).isZero();
        JsonNode invoice = data(call(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200);
        assertThat(invoice.path("lastSendStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(invoice.path("lastSendStatusLabel").asText()).isEqualTo("Envoi incertain");
        assertThat(invoice.path("lastSendError").asText())
                .isEqualTo("Envoi incertain : vérifiez auprès du client avant de renvoyer");
        assertThat(invoice.path("sendCount").asInt()).isZero();

        smsQuota(0);
        Map<String, Object> overQuota = sendOp(id, payload(id, "channel", "SMS"));
        push(ownerToken, overQuota);
        awaitStatus((String) overQuota.get("operationId"), "FAILED");
        assertThat(requests(schema, (String) overQuota.get("operationId")).get(0).get("last_error_code"))
                .isEqualTo("QUOTA_EXCEEDED");
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200).path("lastSendError").asText())
                .isEqualTo("Quota de votre offre atteint");

        ReflectionTestUtils.setField((Object) AopTestUtils.getUltimateTargetObject(outbox), "publicBaseUrl", "");
        jdbc.update("DELETE FROM public.plan_quotas WHERE plan_type = ?", PLAN);
        catalogStore.invalidate();
        Map<String, Object> noBase = sendOp(id, payload(id, "channel", "SMS"));
        push(ownerToken, noBase);
        awaitStatus((String) noBase.get("operationId"), "FAILED");
        assertThat(requests(schema, (String) noBase.get("operationId")).get(0).get("last_error_code"))
                .isEqualTo("PUBLIC_BASE_URL_MISSING");
        assertThat(sms.sent).isEmpty();
    }

    @Test
    @DisplayName("Poller over two tenants: the due requests of each are claimed set-based and sent once")
    void pollerTwoTenants() throws Exception {
        String id = issued("677000111", null);
        String other = tx.execute(st -> tenantFactory.create()).getSchemaName();
        UUID otherTenant = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, other);
        try {
            String otherToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider,
                    UUID.randomUUID(), other, "OWNER");
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("legalName", "Quincaillerie Bona");
            st.put("vatRegistered", false);
            st.put("vatRate", 19.25);
            st.put("pricesIncludeVat", true);
            data(callAs(otherToken, HttpMethod.PUT, "/api/v1/invoicing/settings", st), 200);
            Map<String, Object> draft = Map.of("client", Map.of("name", "Client Bona", "phone", "690000002"),
                    "lines", List.of(Map.of("description", "Clous", "quantity", 1, "unitPrice", 1_000,
                            "vatTreatment", "STANDARD")));
            String otherId = data(callAs(otherToken, HttpMethod.POST, "/api/v1/invoices", draft), 201).path("id").asText();
            data(callAs(otherToken, HttpMethod.POST, "/api/v1/invoices/" + otherId + "/issue", null), 200);
            for (String[] t : new String[][]{{schema, id}, {other, otherId}}) {
                jdbc.update("INSERT INTO \"" + t[0] + "\".invoice_send_requests (id, invoice_id, operation_id, channel, "
                        + "status, attempts, next_attempt_at) VALUES (?, ?::uuid, ?, 'SMS', 'PENDING', 0, "
                        + "now() - interval '1 second')", UUID.randomUUID(), t[1], "poll-" + t[0]);
            }

            var report = outboxJob.run();

            assertThat(report.processed()).isGreaterThanOrEqualTo(2);
            assertThat(requests(schema, "poll-" + schema)).singleElement()
                    .satisfies(r -> assertThat(r.get("status")).isEqualTo("SENT"));
            assertThat(requests(other, "poll-" + other)).singleElement()
                    .satisfies(r -> assertThat(r.get("status")).isEqualTo("SENT"));
            assertThat(sms.sent).extracting(SmsMessage::to).containsExactlyInAnyOrder("+237677000111", "+237690000002");
            assertThat(outboxJob.run().processed()).isZero();
        } finally {
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", otherTenant);
            jdbc.update("DELETE FROM public.usage_counters WHERE tenant_id = ?", otherTenant);
        }
    }
}
