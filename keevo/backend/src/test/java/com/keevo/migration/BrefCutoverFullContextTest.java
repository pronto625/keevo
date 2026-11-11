package com.keevo.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 24.9 — the cutover end to end through the super-admin API and the production beans, from a synthetic Bref
 * database of its own (structure of {@code migration/bref-source-schema.sql} + monetisation, random ids and phones,
 * no real data): a clean owner, an owner linked to an existing Keevo account (24.7), a disabled owner (nothing to
 * migrate). Run → report → rollback (initial Keevo state, linked account kept) → new run → « go » refused by a tampered
 * owner, accepted with a reason → companies ACTIVE → rollback refused.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("24.9 — cutover command: run, report, rollback, « go »")
class BrefCutoverFullContextTest extends FullContextIntegrationTest {

    private static final String BREF_DB = "bref_src_it249";
    private static final Instant T0 = Instant.parse("2026-03-01T08:00:00.123Z");

    static {
        SharedPostgresContainer.freshDatabase(BREF_DB);
    }

    @DynamicPropertySource
    static void brefSource(DynamicPropertyRegistry registry) {
        registry.add("keevo.migration.bref.jdbc.url", () -> SharedPostgresContainer.jdbcUrl(BREF_DB));
        registry.add("keevo.migration.bref.jdbc.username", SharedPostgresContainer::username);
        registry.add("keevo.migration.bref.jdbc.password", SharedPostgresContainer::password);
    }

    private static JdbcTemplate bref;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JdbcTemplate jdbc;

    private final UUID adminId = UUID.randomUUID();

    @BeforeAll
    static void brefSchema() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource(SharedPostgresContainer.jdbcUrl(BREF_DB),
                SharedPostgresContainer.username(), SharedPostgresContainer.password());
        bref = new JdbcTemplate(ds);
        bref.execute(new ClassPathResource("migration/bref-source-schema.sql").getContentAsString(StandardCharsets.UTF_8));
        bref.execute(new ClassPathResource("migration/bref-source-monetisation-schema.sql")
                .getContentAsString(StandardCharsets.UTF_8));
        bref.execute(new ClassPathResource("migration/bref-source-annexes-schema.sql")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static final String HASH = "$2b$10$abcdefghijklmnopqrstuuJ0pOaKGXUqLqRZ1xWd9ycfhzbMSqHmu";

    /** 2 100 paid sales of one line each: more than one write block (2 000 rows; 5 000 records). */
    private static void manySales(UUID owner, UUID shop) {
        bref.update("INSERT INTO invoice (id, \"userId\", \"shopId\", \"customerName\", subtotal, tax, discount, "
                + "\"deliveryFee\", total, status, \"paymentMethod\", \"initialPayment\", date, type, \"createdAt\", "
                + "\"updatedAt\") SELECT md5(? || g)::uuid, ?, ?, 'Client Test', 1000 + g, 0, 0, 0, 1000 + g, "
                + "'paid'::invoice_status_enum, 'cash', 1000 + g, '2026-03-03', 'sale', ?, ? "
                + "FROM generate_series(1, 2100) g", owner.toString(), owner.toString(), shop.toString(), utc(T0),
                utc(T0));
        bref.update("INSERT INTO invoice_item (id, \"invoiceId\", reference, name, quantity, \"unitPrice\", total) "
                + "SELECT md5(? || 'line' || g)::uuid, md5(? || g)::uuid, 'R', 'Article', 1, 1000 + g, 1000 + g "
                + "FROM generate_series(1, 2100) g", owner.toString(), owner.toString());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static String phone() {
        return "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static UUID owner(String phone, boolean active) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO \"user\" (id, phone, role, \"isActive\", \"isVerified\", \"createdAt\", \"updatedAt\") "
                + "VALUES (?, ?, 'owner', ?, true, ?, ?)", id, phone, active, utc(T0), utc(T0));
        return id;
    }

    private static UUID shop(UUID owner) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO shop (id, \"userId\", name, address, category, country, \"createdAt\", \"updatedAt\") "
                + "VALUES (?, ?, 'Boutique test', 'Adresse', 'food', 'CM', ?, ?)", id, owner, utc(T0), utc(T0));
        return id;
    }

    private static UUID sale(UUID owner, UUID shop, String total) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO invoice (id, \"userId\", \"shopId\", \"customerName\", subtotal, tax, discount, "
                        + "\"deliveryFee\", total, status, \"paymentMethod\", \"initialPayment\", date, type, \"createdAt\", "
                        + "\"updatedAt\") VALUES (?, ?, ?, 'Client Test', ?::numeric, 0, 0, 0, ?::numeric, "
                        + "'paid'::invoice_status_enum, 'cash', ?::numeric, '2026-03-02', 'sale', ?, ?)",
                id, owner.toString(), shop.toString(), total, total, total, utc(T0), utc(T0));
        bref.update("INSERT INTO invoice_item (id, \"invoiceId\", reference, name, quantity, \"unitPrice\", total) "
                + "VALUES (?, ?, 'R', 'Article', 1, ?::numeric, ?::numeric)", UUID.randomUUID(), id, total, total);
        return id;
    }

    private String token() {
        return TestAccess.token(jdbc, jwtTokenProvider, adminId, "public", "SUPER_ADMIN");
    }

    private MvcResult send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r, String body)
            throws Exception {
        return mockMvc.perform(r.header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : body)).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode waitFor(String id, Set<String> statuses) throws Exception {
        long deadline = System.currentTimeMillis() + 180_000;
        while (true) {
            JsonNode c = json(send(get("/api/v1/admin/migration/cutovers/" + id), null)).path("data");
            if (statuses.contains(c.path("status").asText())) {
                return c;
            }
            assertThat(System.currentTimeMillis()).as("cutover still %s", c.path("status").asText())
                    .isLessThan(deadline);
            Thread.sleep(250);
        }
    }

    /** The companies of these owners still suspended by the import (the shared test database holds other imports). */
    private static final String SUSPENDED_BY_IMPORT = "SELECT count(*) FROM public.tenants t JOIN public.migration_records r "
            + "ON r.target_id = t.id AND r.target_table = 'tenants' WHERE r.bref_owner_id IN (?, ?) "
            + "AND t.suspended_reason LIKE 'Migration Bref%'";

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private String state() {
        StringBuilder out = new StringBuilder();
        for (String table : new String[] {"tenants", "users", "user_tenant_memberships", "migration_records",
                "migration_batches", "tenant_plan_events", "tenant_payments", "payment_requests", "user_authenticators",
                "web_push_subscriptions", "membership_invitations", "invoice_legacy_links", "account_deletion_log"}) {
            out.append(table).append('=').append(count("SELECT count(*) FROM public." + table)).append(' ');
        }
        return out.toString();
    }

    @Test
    @DisplayName("run → report → rollback to the initial state → run → « go » gated, accepted, active; no rollback after")
    void cutoverEndToEnd() throws Exception {
        token(); // the super-admin row exists before the initial state is read
        UUID clean = owner(phone(), true);
        UUID cleanShop = shop(clean);
        sale(clean, cleanShop, "25000");
        manySales(clean, cleanShop);
        bref.update("UPDATE \"user\" SET \"socialId\" = ?, provider = 'google' WHERE id = ?", "g-" + clean, clean);
        String linkedPhone = phone();
        UUID linked = owner(linkedPhone, true);
        UUID linkedShop = shop(linked);
        sale(linked, linkedShop, "12000");
        bref.update("INSERT INTO subscription (id, \"ownerId\", plan, status, \"billingCycle\", \"startedAt\", "
                        + "\"endsAt\", \"amountPaid\", \"creditUsed\", \"paymentMethod\", \"createdAt\", \"updatedAt\") "
                        + "VALUES (?, ?, 'pro', 'active', 'monthly', ?, ?, 3000, 0, 'momo', ?, ?)", UUID.randomUUID(),
                linked, utc(Instant.now().minusSeconds(5 * 86400)), utc(Instant.now().plusSeconds(25 * 86400)),
                utc(Instant.now().minusSeconds(5 * 86400)), utc(Instant.now().minusSeconds(5 * 86400)));
        bref.update("INSERT INTO web_push_subscription (id, \"userId\", endpoint, p256dh, auth, \"createdAt\", "
                + "\"updatedAt\") VALUES (?, ?, ?, 'p256dh-test', 'auth-test', ?, ?)", UUID.randomUUID(),
                linked.toString(), "https://push.example.invalid/it249-" + linked, utc(T0), utc(T0));
        // a Bref seller whose phone (not confirmed in Bref) belongs to a Keevo account: invited (24.7)
        String invitedPhone = phone();
        UUID invitedKeevo = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, phone_number, "
                + "role) VALUES (?, now(), now(), true, 0, ?, 'OWNER')", invitedKeevo, invitedPhone);
        bref.update("INSERT INTO \"user\" (id, password, \"firstName\", \"lastName\", phone, role, \"shopId\", "
                        + "\"isActive\", \"isVerified\", \"createdAt\", \"updatedAt\") VALUES (?, ?, 'Prénom', 'Nom', ?, "
                        + "'seller', ?, true, false, ?, ?)", UUID.randomUUID(), HASH, invitedPhone,
                linkedShop.toString(), utc(T0), utc(T0));
        UUID disabled = owner(phone(), false);
        shop(disabled);
        // an existing Keevo account with the linked owner's phone (proven: Bref phone verified) — its own company
        assertThat(mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + linkedPhone + "\",\"password\":\"Keevo-it249-pass\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
        UUID keevoAccount = jdbc.queryForObject("SELECT id FROM public.users WHERE phone_number = ?", UUID.class,
                linkedPhone);
        String initial = state();

        // EXPORT refused, run started
        assertThat(send(post("/api/v1/admin/migration/cutovers"), "{\"source\":\"EXPORT\"}").getResponse()
                .getStatus()).isEqualTo(422);
        MvcResult started = send(post("/api/v1/admin/migration/cutovers"), "{\"source\":\"DATABASE\",\"parallelism\":2}");
        assertThat(started.getResponse().getStatus()).isEqualTo(202);
        String id = json(started).path("data").path("id").asText();
        assertThat(send(post("/api/v1/admin/migration/cutovers"), "{\"source\":\"DATABASE\"}").getResponse()
                .getStatus()).isEqualTo(409);
        JsonNode ready = waitFor(id, Set.of("READY", "FAILED"));
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        JsonNode report = ready.path("report");
        assertThat(report.path("ownersTotal").asInt()).isEqualTo(3);
        assertThat(report.toString()).contains("Importé et réconcilié", "Non repris (rien à migrer)",
                "Ventes non annulées");
        assertThat(ready.path("progress").toString()).contains("\"RECONCILED\"").doesNotContain("GAP", "FAILED");
        assertThat(count("SELECT count(*) FROM public.user_authenticators")).isGreaterThan(0);
        assertThat(count("SELECT count(*) FROM public.web_push_subscriptions WHERE user_id = ?", keevoAccount))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.membership_invitations WHERE user_id = ?", invitedKeevo))
                .isEqualTo(1);
        assertThat(report.toString()).doesNotContain(clean.toString(), linked.toString(), keevoAccount.toString());
        assertThat(report.path("durations").path("runWallMs").asLong()).isPositive();
        JsonNode owners = json(send(get("/api/v1/admin/migration/cutovers/" + id + "/owners?status=RECONCILED"),
                null)).path("data");
        assertThat(owners).hasSize(2);
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE user_id = ?", keevoAccount))
                .isEqualTo(2);
        assertThat(json(send(get("/api/v1/admin/migration/batches?limit=10"), null)).path("data").size())
                .isGreaterThanOrEqualTo(3);

        // rollback: the Keevo state is the initial one, the linked account keeps its own company
        assertThat(send(post("/api/v1/admin/migration/cutovers/" + id + "/rollback"), "{\"confirmation\":\"non\"}")
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(send(post("/api/v1/admin/migration/cutovers/" + id + "/rollback"),
                "{\"confirmation\":\"RETOUR ARRIERE\"}").getResponse().getStatus()).isEqualTo(202);
        assertThat(waitFor(id, Set.of("ROLLED_BACK", "FAILED")).path("status").asText()).isEqualTo("ROLLED_BACK");
        assertThat(state()).isEqualTo(initial);
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE user_id = ?", keevoAccount))
                .isEqualTo(1);

        // a new run; one company tampered → the « go » is refused, then accepted with a documented reason
        String second = json(send(post("/api/v1/admin/migration/cutovers"), "{\"source\":\"DATABASE\"}"))
                .path("data").path("id").asText();
        assertThat(waitFor(second, Set.of("READY", "FAILED")).path("status").asText()).isEqualTo("READY");
        String schema = jdbc.queryForObject("SELECT t.schema_name FROM public.tenants t JOIN public.migration_records r "
                + "ON r.target_id = t.id AND r.target_table = 'tenants' WHERE r.bref_owner_id = ?", String.class, clean);
        jdbc.update("UPDATE \"" + schema + "\".invoices SET total_ttc = total_ttc + 50");
        UUID batch = jdbc.queryForObject("SELECT id FROM public.migration_batches WHERE bref_owner_id = ? "
                + "ORDER BY started_at DESC LIMIT 1", UUID.class, clean);
        send(post("/api/v1/admin/migration/batches/" + batch + "/reconciliation"), null);
        MvcResult refused = send(post("/api/v1/admin/migration/cutovers/" + second + "/activation"),
                "{\"confirmation\":\"ACTIVER\"}");
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        JsonNode blocking = json(refused).path("details").path("blockingOwners");
        assertThat(blocking).hasSize(1);
        assertThat(blocking.get(0).path("reason").asText()).isEqualTo("Écart de réconciliation à corriger");
        assertThat(count(SUSPENDED_BY_IMPORT, clean, linked)).isEqualTo(2);

        MvcResult go = send(post("/api/v1/admin/migration/cutovers/" + second + "/activation"),
                "{\"confirmation\":\"ACTIVER\",\"acceptedOwners\":[{\"ownerId\":\"" + clean
                        + "\",\"reason\":\"Écart documenté par la répétition générale\"}]}");
        assertThat(go.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(go).path("data").path("status").asText()).isEqualTo("ACTIVATED");
        assertThat(count("SELECT count(*) FROM public.membership_invitations WHERE user_id = ? AND status = 'PENDING' "
                + "AND expires_at > now() + interval '6 days' AND invited_at > now() - interval '1 minute' "
                + "AND last_sent_at < now() - interval '23 hours'", invitedKeevo)).isEqualTo(1);
        assertThat(count(SUSPENDED_BY_IMPORT, clean, linked)).isZero();
        assertThat(count("SELECT count(*) FROM public.tenants t JOIN public.migration_records r ON r.target_id = t.id "
                + "AND r.target_table = 'tenants' WHERE r.bref_owner_id IN (?, ?) AND t.status = 'ACTIVE'", clean,
                linked)).isEqualTo(2);
        assertThat(send(post("/api/v1/admin/migration/cutovers/" + second + "/rollback"),
                "{\"confirmation\":\"RETOUR ARRIERE\"}").getResponse().getStatus()).isEqualTo(409);
        assertThat(send(get("/api/v1/admin/migration/cutovers/" + UUID.randomUUID()), null).getResponse()
                .getStatus()).isEqualTo(404);
    }
}
