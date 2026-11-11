package com.keevo.clientportal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.clientportal.auth.application.PortalSessionPurgeJob;
import com.keevo.clientportal.auth.domain.model.PortalTokens;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.invoicing.invoice.adapter.in.bootstrap.InvoicePhoneIndexBackfillJob;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 21.7 — the client portal end to end on PostgreSQL with the production beans: sign-in by a portal code (never
 * usable as a merchant sign-in code and vice versa, no merchant account created), the invoices of one phone across two
 * merchants (each read in its own schema, minimum fields, keyset pages, link opening the PDF), never another phone's,
 * the owner's switch, a suspended merchant, a cancelled invoice, the session refusals (none, unknown, merchant JWT,
 * portal token on a merchant endpoint), sign-out, and the one-shot back-fill.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Client portal — sign-in, invoices across merchants, switch, isolation, back-fill (21.7)")
class ClientPortalFullContextTest extends FullContextIntegrationTest {

    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry moduleRegistry;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired InvoicePhoneIndexBackfillJob backfill;
    @Autowired PortalSessionPurgeJob purgeJob;

    record Owner(String schema, String jwt) {
    }

    private final List<String> schemas = new ArrayList<>();
    private Owner a;
    private Owner b;
    private String phone;
    private String otherPhone;

    @BeforeEach
    void setUp() {
        a = owner(newTenant());
        b = owner(newTenant());
        phone = randomPhone();
        otherPhone = randomPhone();
    }

    @AfterEach
    void tearDown() {
        for (String s : schemas) {
            jdbc.update("UPDATE public.tenants SET status = 'ACTIVE' WHERE schema_name = ?", s);
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

    private Owner owner(String tenant) {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                + "phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, 'OWNER')", user, randomPhone());
        return new Owner(tenant, TestAccess.token(jdbc, jwtTokenProvider, user, tenant, "OWNER"));
    }

    private static String randomPhone() {
        return "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String bearer, Object body) throws Exception {
        return callFrom("10.21.7." + (IP.getAndIncrement() % 250 + 1), request, bearer, body);
    }

    /** Same as {@link #call} from a fixed client address (per-IP brakes). */
    private MvcResult callFrom(String ip, MockHttpServletRequestBuilder request, String bearer, Object body)
            throws Exception {
        request.header("X-Forwarded-For", ip);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        MvcResult r = mockMvc.perform(request).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return r;
    }

    private MvcResult rest(Owner o, HttpMethod method, String path, Object body) throws Exception {
        return call(MockMvcRequestBuilders.request(method, path), o.jwt(), body);
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

    private String issuedInvoice(Owner o, String clientPhone) throws Exception {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", "Jean Mbarga");
        client.put("phone", clientPhone);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 2);
        line.put("unitPrice", 11_925);
        line.put("vatTreatment", "STANDARD");
        String id = data(rest(o, HttpMethod.POST, "/api/v1/invoices", Map.of("client", client, "lines",
                List.of(line))), 201).path("id").asText();
        data(rest(o, HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        return id;
    }

    private void setKnownCode(String key, String code) {
        jdbc.update("UPDATE public.otp_challenges SET code_hash = ? WHERE identifier = ? AND consumed_at IS NULL",
                passwordEncoder.encode(code), key);
    }

    /** Portal sign-in of {@code phoneNumber} (code request, known code, check) → the session token. */
    private String signIn(String phoneNumber) throws Exception {
        data(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/otp/request"), null,
                Map.of("phone", phoneNumber)), 200);
        setKnownCode("portal:" + phoneNumber, "424242");
        JsonNode session = data(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/otp/verify"), null,
                Map.of("phone", phoneNumber, "code", "424242")), 200);
        String token = session.path("accessToken").asText();
        assertThat(token).startsWith("kvc_").hasSize(47);
        assertThat(session.path("expiresAt").asText()).isNotBlank();
        return token;
    }

    private MvcResult list(String token, String query) throws Exception {
        return call(MockMvcRequestBuilders.get("/api/v1/portal/invoices" + (query == null ? "" : "?" + query)),
                token, null);
    }

    private List<String> references(JsonNode page) {
        List<String> refs = new ArrayList<>();
        page.path("items").forEach(i -> refs.add(i.path("merchantName").asText() + "|" + i.path("reference").asText()));
        return refs;
    }

    private String number(Owner o, String invoiceId) {
        return jdbc.queryForObject("SELECT number FROM \"" + o.schema() + "\".invoices WHERE id = ?::uuid",
                String.class, invoiceId);
    }

    private String merchant(Owner o, String invoiceId) {
        return jdbc.queryForObject("SELECT seller_legal_name FROM \"" + o.schema() + "\".invoices WHERE id = ?::uuid",
                String.class, invoiceId);
    }

    private void switchPortal(Owner o, boolean on) throws Exception {
        data(rest(o, HttpMethod.PUT, "/api/v1/tenants/me/modules/client-portal/activation",
                Map.of("activated", on)), 200);
    }

    // ── sign-in ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sign-in: portal code opens a portal session only; a merchant code never opens the portal")
    void codesStayInTheirPurpose() throws Exception {
        // a portal code typed on the merchant sign-in: refused, no merchant account created
        data(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/otp/request"), null,
                Map.of("phone", phone.substring(4))), 200); // 9-digit local number → +237…
        setKnownCode("portal:" + phone, "135790");
        assertError(call(MockMvcRequestBuilders.post("/api/v1/auth/otp/verify"), null,
                Map.of("identifier", phone, "code", "135790")), 401, "OTP_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.users WHERE phone_number = ?", Integer.class,
                phone)).isZero();

        // a merchant code typed on the portal: refused
        data(call(MockMvcRequestBuilders.post("/api/v1/auth/otp/request"), null, Map.of("identifier", otherPhone)),
                200);
        setKnownCode(otherPhone, "246802");
        assertError(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/otp/verify"), null,
                Map.of("phone", otherPhone, "code", "246802")), 401, "OTP_INVALID");

        // the portal code on the portal: one client, one session (hash only), still no merchant account
        JsonNode session = data(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/otp/verify"), null,
                Map.of("phone", phone, "code", "135790")), 200);
        String token = session.path("accessToken").asText();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.portal_clients WHERE phone = ?", Integer.class,
                phone)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.portal_sessions s JOIN public.portal_clients c "
                + "ON c.id = s.client_id WHERE c.phone = ? AND length(s.token_hash) = 32", Integer.class, phone))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.users WHERE phone_number = ?", Integer.class,
                phone)).isZero();

        // the code is used: replay refused; email refused
        assertError(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/otp/verify"), null,
                Map.of("phone", phone, "code", "135790")), 401, "OTP_INVALID");
        assertError(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/otp/request"), null,
                Map.of("phone", "client@example.com")), 422, "VALIDATION_FAILED");

        // sign-out: 204, then the token is refused; a second sign-out is still 204
        assertThat(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/logout"), token, null).getResponse()
                .getStatus()).isEqualTo(204);
        assertError(list(token, null), 401, "PORTAL_SESSION_INVALID");
        assertThat(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/logout"), token, null).getResponse()
                .getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("sessions: none, made-up, expired, merchant JWT → 401 here; a portal token on a merchant endpoint → 401")
    void sessionRefusals() throws Exception {
        assertError(list(null, null), 401, "PORTAL_SESSION_INVALID");
        assertError(list("kvc_" + "A".repeat(43), null), 401, "PORTAL_SESSION_INVALID");
        assertError(list(a.jwt(), null), 401, "PORTAL_SESSION_INVALID");

        String token = signIn(phone);
        assertThat(call(MockMvcRequestBuilders.get("/api/v1/auth/profile"), token, null).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(call(MockMvcRequestBuilders.get("/api/v1/invoices"), token, null).getResponse().getStatus())
                .isEqualTo(401);

        jdbc.update("UPDATE public.portal_sessions SET created_at = now() - interval '31 days', expires_at = now() - "
                + "interval '1 day' WHERE client_id = (SELECT id FROM public.portal_clients WHERE phone = ?)", phone);
        assertError(list(token, null), 401, "PORTAL_SESSION_INVALID");
    }

    // ── listing ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("invoices of one phone across two merchants, newest first, keyset pages, minimum fields, link opens the PDF")
    void invoicesAcrossMerchants() throws Exception {
        String a1 = issuedInvoice(a, phone);
        String foreign = issuedInvoice(a, otherPhone);
        String b1 = issuedInvoice(b, phone.substring(4)); // typed without +237 by the merchant: same client
        String a2 = issuedInvoice(a, phone);
        String token = signIn(phone);

        JsonNode first = data(list(token, "limit=2"), 200);
        assertThat(references(first)).containsExactly(merchant(a, a2) + "|" + number(a, a2),
                merchant(b, b1) + "|" + number(b, b1));
        String cursor = first.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        JsonNode second = data(list(token, "limit=2&cursor=" + cursor), 200);
        assertThat(references(second)).containsExactly(merchant(a, a1) + "|" + number(a, a1));
        assertThat(second.path("nextCursor").isNull()).isTrue();

        JsonNode item = first.path("items").get(0);
        assertThat(item.path("totalTtc").asLong()).isPositive();
        assertThat(item.path("paymentState").asText()).isEqualTo("UNPAID");
        assertThat(item.path("paymentStateLabel").asText()).isEqualTo("Non payée");
        assertThat(item.path("issueDate").asText()).isNotBlank();
        assertThat(item.path("link").path("expiresAt").asText()).isNotBlank();
        String url = item.path("link").path("url").asText();
        assertThat(url).contains("/api/v1/invoices/shared?token=");

        // nothing technical, nothing of the other phone
        String text = list(token, null).getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).doesNotContain(a1, a2, b1, foreign, a.schema(), b.schema(), "Jean Mbarga")
                .doesNotContain(number(a, foreign));
        assertThat(references(data(list(token, null), 200))).hasSize(3);

        // the link opens the PDF (no JWT)
        MvcResult pdf = call(MockMvcRequestBuilders.get(url.substring(url.indexOf("/api/"))), null, null);
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(pdf.getResponse().getContentType()).startsWith("application/pdf");

        // the other phone sees only its own invoice
        String other = signIn(otherPhone);
        assertThat(references(data(list(other, null), 200))).containsExactly(merchant(a, foreign) + "|"
                + number(a, foreign));

        // bad cursor / limit → 400
        assertError(list(token, "cursor=not-a-cursor"), 400, "VALIDATION_ERROR");
        assertError(list(token, "limit=51"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("owner switch off → the merchant's invoices disappear, on → back; suspended merchant hidden; cancelled shown without link")
    void switchSuspensionAndCancelled() throws Exception {
        String a1 = issuedInvoice(a, phone);
        String b1 = issuedInvoice(b, phone);
        String token = signIn(phone);
        assertThat(references(data(list(token, null), 200))).hasSize(2);

        switchPortal(a, false);
        assertThat(references(data(list(token, null), 200))).containsExactly(merchant(b, b1) + "|" + number(b, b1));
        switchPortal(a, true);
        assertThat(references(data(list(token, null), 200))).hasSize(2);

        jdbc.update("UPDATE public.tenants SET status = 'SUSPENDED' WHERE schema_name = ?", b.schema());
        assertThat(references(data(list(token, null), 200))).containsExactly(merchant(a, a1) + "|" + number(a, a1));
        jdbc.update("UPDATE public.tenants SET status = 'ACTIVE' WHERE schema_name = ?", b.schema());

        data(rest(a, HttpMethod.POST, "/api/v1/invoices/" + a1 + "/cancel", Map.of("reason", "Erreur de saisie")),
                200);
        JsonNode page = data(list(token, null), 200);
        JsonNode cancelled = null;
        for (JsonNode i : page.path("items")) {
            if (i.path("reference").asText().equals(number(a, a1))) {
                cancelled = i;
            }
        }
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.path("paymentState").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("paymentStateLabel").asText()).isEqualTo("Annulée");
        assertThat(cancelled.path("link").isNull()).isTrue();

        // an invoice replaced after a sale correction is no longer listed (its replacement is)
        jdbc.update("UPDATE \"" + b.schema() + "\".invoices SET superseded_at = now() WHERE id = ?::uuid", b1);
        assertThat(references(data(list(token, null), 200))).containsExactly(merchant(a, a1) + "|" + number(a, a1));
    }

    @Test
    @DisplayName("activation rows: none = on (new company); an owner-disabled row (what V70 writes for existing ones) = off")
    void activationRows() throws Exception {
        issuedInvoice(a, phone);
        String token = signIn(phone);
        assertThat(references(data(list(token, null), 200))).hasSize(1);
        jdbc.update("INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at) "
                + "SELECT id, 'client-portal', false, now() FROM public.tenants WHERE schema_name = ? "
                + "ON CONFLICT (tenant_id, module_id) DO UPDATE SET activated = false", a.schema());
        moduleRegistry.evict(a.schema());
        assertThat(references(data(list(token, null), 200))).isEmpty();
    }

    @Test
    @DisplayName("a super-admin DISABLED module row hides the merchant like the owner's switch")
    void moduleRowDisabled() throws Exception {
        issuedInvoice(a, phone);
        String token = signIn(phone);
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) SELECT id, 'client-portal', "
                + "'DISABLED', 'FREE' FROM public.tenants WHERE schema_name = ? ON CONFLICT (tenant_id, module_id) DO "
                + "UPDATE SET status = 'DISABLED'", a.schema());
        try {
            assertThat(references(data(list(token, null), 200))).isEmpty();
        } finally {
            jdbc.update("DELETE FROM public.tenant_modules WHERE module_id = 'client-portal' AND tenant_id = (SELECT id "
                    + "FROM public.tenants WHERE schema_name = ?)", a.schema());
        }
        assertThat(references(data(list(token, null), 200))).hasSize(1);
    }

    // ── code attempts, brakes, sessions ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("portal code: wrong codes are counted (kept although the call fails), the 5th kills the code")
    void wrongCodesAreCounted() throws Exception {
        data(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/otp/request"), null, Map.of("phone", phone)), 200);
        setKnownCode("portal:" + phone, "112233");
        for (int i = 1; i <= 4; i++) {
            assertError(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/otp/verify"), null,
                    Map.of("phone", phone, "code", "000000")), 401, "OTP_INVALID");
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM public.otp_challenges WHERE identifier = ?",
                Integer.class, "portal:" + phone)).isEqualTo(4);
        assertError(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/otp/verify"), null,
                Map.of("phone", phone, "code", "000000")), 429, "OTP_TOO_MANY_ATTEMPTS");
        assertError(call(MockMvcRequestBuilders.post("/api/v1/portal/auth/otp/verify"), null,
                Map.of("phone", phone, "code", "112233")), 401, "OTP_INVALID");
    }

    @Test
    @DisplayName("brakes: 30 made-up tokens from one IP → 429, a valid session from that IP still served; 61st call of a session → 429")
    void brakes() throws Exception {
        String token = signIn(phone);
        String ip = "10.217.99." + ThreadLocalRandom.current().nextInt(1, 250);
        for (int i = 0; i < 30; i++) {
            assertError(callFrom(ip, MockMvcRequestBuilders.get("/api/v1/portal/invoices"), PortalTokens.generate(),
                    null), 401, "PORTAL_SESSION_INVALID");
        }
        assertError(callFrom(ip, MockMvcRequestBuilders.get("/api/v1/portal/invoices"), PortalTokens.generate(), null),
                429, "PORTAL_TOO_MANY_REQUESTS");
        assertError(callFrom(ip, MockMvcRequestBuilders.get("/api/v1/portal/invoices"), "garbage", null),
                429, "PORTAL_TOO_MANY_REQUESTS");
        // the IP's failures never block a valid session behind the same address (carrier NAT)
        data(callFrom(ip, MockMvcRequestBuilders.get("/api/v1/portal/invoices"), token, null), 200);
        for (int i = 2; i <= 60; i++) {
            data(list(token, null), 200);
        }
        assertError(list(token, null), 429, "PORTAL_TOO_MANY_REQUESTS");
    }

    @Test
    @DisplayName("sessions: at most 10 per client (the oldest goes at sign-in); the nightly purge drops expired ones only")
    void sessionCapAndPurge() throws Exception {
        String first = signIn(phone);
        UUID client = jdbc.queryForObject("SELECT id FROM public.portal_clients WHERE phone = ?", UUID.class, phone);
        jdbc.update("UPDATE public.portal_sessions SET created_at = now() - interval '2 days' WHERE client_id = ?",
                client);
        for (int i = 0; i < 9; i++) {
            jdbc.update("INSERT INTO public.portal_sessions (token_hash, client_id, created_at, expires_at) VALUES "
                    + "(?, ?, now() - interval '1 hour', now() + interval '29 days')",
                    PortalTokens.hash(PortalTokens.generate()), client);
        }
        String newest = signIn(phone);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.portal_sessions WHERE client_id = ?",
                Integer.class, client)).isEqualTo(10);
        assertError(list(first, null), 401, "PORTAL_SESSION_INVALID");
        data(list(newest, null), 200);

        jdbc.update("UPDATE public.portal_sessions SET created_at = now() - interval '40 days', expires_at = now() - "
                + "interval '10 days' WHERE client_id = ? AND token_hash <> ?", client, PortalTokens.hash(newest));
        purgeJob.purge();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.portal_sessions WHERE client_id = ?",
                Integer.class, client)).isEqualTo(1);
        data(list(newest, null), 200);
    }

    @Test
    @DisplayName("a forged cursor (out-of-range date) is a 400, never a 500")
    void forgedCursor() throws Exception {
        String token = signIn(phone);
        byte[] forged = java.nio.ByteBuffer.allocate(40).putLong(Long.MIN_VALUE).array();
        assertError(list(token, "cursor=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(forged)),
                400, "VALIDATION_ERROR");
    }

    // ── back-fill ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("back-fill: invoices issued before the portal are indexed once; a rerun writes nothing")
    void backfill() throws Exception {
        issuedInvoice(a, phone);
        issuedInvoice(a, otherPhone);
        UUID tenant = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class,
                a.schema());
        jdbc.update("DELETE FROM public.invoice_phone_index WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM public.data_migrations WHERE name = 'client-portal-invoice-phone-index-backfill'");

        backfill.backfillAll();
        assertThat(jdbc.queryForList("SELECT phone_normalized FROM public.invoice_phone_index WHERE tenant_id = ? "
                + "ORDER BY phone_normalized", String.class, tenant))
                .containsExactlyInAnyOrder(phone, otherPhone);
        String token = signIn(phone);
        assertThat(references(data(list(token, null), 200))).hasSize(1);

        jdbc.update("DELETE FROM public.data_migrations WHERE name = 'client-portal-invoice-phone-index-backfill'");
        assertThat(backfill.backfillAll()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.invoice_phone_index WHERE tenant_id = ?",
                Integer.class, tenant)).isEqualTo(2);
    }
}
