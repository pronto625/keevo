package com.keevo.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keevo.migration.domain.model.BrefAnnexes;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefAnnexesSourcePort;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 24.6 — the annex import end to end, through the super-admin API and the production beans, on a synthetic Bref
 * database (structure of {@code migration/bref-source-schema.sql} + {@code migration/bref-source-annexes-schema.sql},
 * rows of the versioned fixture {@code migration/bref-annexes-fixture.sql}: no real data): WhatsApp Business account
 * (token re-encrypted with the Keevo key), templates, campaigns and recipients, inbox and conversations, AI
 * conversations, notifications, client-portal index, module provisioning, the {@code TENANT_IMPORTED} audit entry;
 * re-run writes nothing; the export gives the same section; a number held by another company is not moved.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("24.6 — Bref annex import (WhatsApp, AI, notifications, portal index, provisioning, audit)")
class BrefAnnexesImportFullContextTest extends FullContextIntegrationTest {

    private static final String BREF_DB = "bref_src_it246";
    private static final Path EXPORT_DIR;
    private static final UUID OWNER = UUID.fromString("00000246-0000-4000-8000-000000000001");
    private static final UUID SELLER = UUID.fromString("00000246-0000-4000-8000-000000000002");
    private static final UUID SHOP = UUID.fromString("00000246-0000-4000-8000-000000000011");
    private static final String TOKEN = "EAAG-synthetic-token-246";

    static {
        SharedPostgresContainer.freshDatabase(BREF_DB);
        try {
            EXPORT_DIR = Files.createTempDirectory("bref-export-it246");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void brefSource(DynamicPropertyRegistry registry) {
        registry.add("keevo.migration.bref.jdbc.url", () -> SharedPostgresContainer.jdbcUrl(BREF_DB));
        registry.add("keevo.migration.bref.jdbc.username", SharedPostgresContainer::username);
        registry.add("keevo.migration.bref.jdbc.password", SharedPostgresContainer::password);
        registry.add("keevo.migration.bref.export-dir", EXPORT_DIR::toString);
    }

    private static JdbcTemplate bref;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecretCipher cipher;
    @Autowired List<BrefAnnexesSourcePort> annexSources;

    private final UUID adminId = UUID.randomUUID();

    @BeforeAll
    static void brefSchema() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource(SharedPostgresContainer.jdbcUrl(BREF_DB),
                SharedPostgresContainer.username(), SharedPostgresContainer.password());
        bref = new JdbcTemplate(ds);
        bref.execute(new ClassPathResource("migration/bref-source-schema.sql").getContentAsString(StandardCharsets.UTF_8));
        bref.execute(new ClassPathResource("migration/bref-source-annexes-schema.sql")
                .getContentAsString(StandardCharsets.UTF_8));
        bref.execute(new ClassPathResource("migration/bref-annexes-fixture.sql")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private MvcResult importOwner(UUID owner, String source) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/migration/bref/owners/" + owner + "/imports")
                .header("Authorization", "Bearer " + TestAccess.token(jdbc, jwtTokenProvider, adminId, "public",
                        "SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"" + source + "\"}")).andReturn();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private long number(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private BrefAnnexesSourcePort source(SourceKind kind) {
        return annexSources.stream().filter(s -> s.kind() == kind).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("fixture owner: every annex imported (token re-encrypted, campaigns never resumed, conversations "
            + "rebuilt), portal index, provisioning, one audit entry; re-run writes nothing; export = database")
    void fixtureOwner() throws Exception {
        JsonNode batch = data(importOwner(OWNER, "DATABASE"));
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        JsonNode counts = batch.path("counts");
        assertThat(counts.path("whatsappAccountsImported").asInt()).isEqualTo(1);
        assertThat(counts.path("whatsappTemplatesImported").asInt()).isEqualTo(3);
        assertThat(counts.path("whatsappCampaignsImported").asInt()).isEqualTo(2);
        assertThat(counts.path("whatsappRecipientsImported").asInt()).isEqualTo(2);
        assertThat(counts.path("whatsappMessagesImported").asInt()).isEqualTo(4);
        assertThat(counts.path("aiConversationsImported").asInt()).isEqualTo(2);
        assertThat(counts.path("notificationsImported").asInt()).isEqualTo(3);
        assertThat(counts.path("webPushSubscriptionsImported").asInt()).isEqualTo(2);
        assertThat(batch.path("notes").toString()).contains("WHATSAPP_TEMPLATE_UNREADABLE",
                "WHATSAPP_TEMPLATE_RESERVED", "WHATSAPP_TEMPLATE_MEDIA_DROPPED", "CAMPAIGN_SCHEDULE_DROPPED",
                "CAMPAIGN_RECIPIENT_DUPLICATE", "CHAT_MESSAGE_UNREADABLE").doesNotContain(TOKEN).doesNotContain("+2376");

        UUID tenantId = UUID.fromString(batch.path("tenantId").asText());
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class,
                tenantId);
        String q = "\"" + schema + "\".";

        // WABA: the company's, the token encrypted with the Keevo key under the company id
        Map<String, Object> account = jdbc.queryForMap("SELECT waba_id, phone_number_id, verified_name, connected_by, "
                + "access_token_enc FROM public.whatsapp_accounts WHERE tenant_id = ?", tenantId);
        assertThat(account.get("waba_id")).isEqualTo("2460000000000001");
        assertThat(account.get("phone_number_id")).isEqualTo("2460000000000002");
        assertThat(account.get("connected_by")).isEqualTo(OWNER);
        byte[] enc = (byte[]) account.get("access_token_enc");
        assertThat(new String(enc, StandardCharsets.ISO_8859_1)).doesNotContain(TOKEN);
        assertThat(cipher.decrypt(enc, tenantId.toString())).isEqualTo(TOKEN);

        // templates: 3 owner templates + the system invoice template, pending until Meta is read
        assertThat(jdbc.queryForList("SELECT name || ':' || status || ':' || system FROM " + q
                + "whatsapp_templates ORDER BY name", String.class)).containsExactly("bref_facture:PENDING:true",
                "nouveautes_media:DRAFT:false", "promo_novembre:APPROVED:false", "relance_refusee:REJECTED:false");
        assertThat(jdbc.queryForObject("SELECT array_to_string(body_examples, '|') FROM " + q + "whatsapp_templates "
                + "WHERE name = 'promo_novembre'", String.class)).isEqualTo("Awa|Boutique");

        // campaigns: never resumed (no job), settings mapped
        Map<String, Object> sent = jdbc.queryForMap("SELECT status, store_ids::text AS stores, template_id, "
                + "variables::text AS variables, inactive_for_days, total_spent_min, sent_count, created_by FROM " + q
                + "whatsapp_campaigns WHERE id = '00000246-0000-4000-8000-000000000401'");
        assertThat(sent.get("status")).isEqualTo("SENT");
        assertThat(sent.get("stores")).isEqualTo("{" + SHOP + "}");
        assertThat(sent.get("template_id")).isEqualTo(UUID.fromString("00000246-0000-4000-8000-000000000301"));
        assertThat((String) sent.get("variables")).contains("CLIENT_NAME", "STORE_NAME");
        assertThat(sent.get("inactive_for_days")).isEqualTo(30);
        assertThat(sent.get("total_spent_min")).isEqualTo(1000L);
        assertThat(sent.get("created_by")).isEqualTo(OWNER);
        assertThat(jdbc.queryForObject("SELECT status || ':' || (scheduled_at IS NULL) FROM " + q + "whatsapp_campaigns "
                + "WHERE id = '00000246-0000-4000-8000-000000000402'", String.class)).isEqualTo("DRAFT:true");
        assertThat(number("SELECT count(*) FROM public.whatsapp_campaign_jobs WHERE tenant_id = ?", tenantId))
                .isZero();
        assertThat(jdbc.queryForList("SELECT phone || ':' || status || ':' || coalesce(wa_message_id, '-') FROM " + q
                + "whatsapp_campaign_recipients ORDER BY phone", String.class))
                .containsExactly("237690024621:SENT:wamid.syn246-c2", "237690024622:FAILED:-");

        // inbox: owner rows only, synthetic Meta id, conversations rebuilt
        assertThat(number("SELECT count(*) FROM " + q + "whatsapp_messages")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT wa_message_id FROM " + q + "whatsapp_messages WHERE id = "
                + "'00000246-0000-4000-8000-000000000604'", String.class))
                .isEqualTo("bref:00000246-0000-4000-8000-000000000604");
        assertThat(jdbc.queryForObject("SELECT sent_by FROM " + q + "whatsapp_messages WHERE id = "
                + "'00000246-0000-4000-8000-000000000603'", UUID.class)).isEqualTo(SELLER);
        Map<String, Object> awa = jdbc.queryForMap("SELECT contact_name, last_message, last_direction, unread_count "
                + "FROM " + q + "whatsapp_conversations WHERE contact_phone = '237690024621'");
        assertThat(awa.get("contact_name")).isEqualTo("Awa S.");
        assertThat(awa.get("last_message")).isEqualTo("Oui, en taille M.");
        assertThat(awa.get("last_direction")).isEqualTo("OUT");
        assertThat(awa.get("unread_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_type || ':' || unread_count FROM " + q + "whatsapp_conversations "
                + "WHERE contact_phone = '237690024623'", String.class)).isEqualTo("image:0");

        // AI conversations and notifications under the Keevo accounts, Bref JSON and read state kept
        assertThat(jdbc.queryForList("SELECT user_id FROM " + q + "ai_conversations ORDER BY created_at", UUID.class))
                .containsExactly(OWNER, SELLER);
        assertThat(jdbc.queryForObject("SELECT messages -> 1 -> 'stats' -> 0 ->> 'value' FROM " + q
                + "ai_conversations WHERE user_id = ?", String.class, OWNER)).isEqualTo("15 000 F");
        assertThat(jdbc.queryForList("SELECT type || ':' || (read_at IS NOT NULL) FROM " + q + "notifications "
                + "ORDER BY created_at", String.class)).containsExactly("system:false", "invoice:false",
                "payment:true");
        assertThat(jdbc.queryForObject("SELECT data ->> 'invoiceId' FROM " + q + "notifications WHERE type = "
                + "'invoice'", String.class)).isEqualTo("00000246-0000-4000-8000-000000000101");

        assertThat(jdbc.queryForList("SELECT user_id FROM public.web_push_subscriptions WHERE id IN "
                + "('00000246-0000-4000-8000-000000000901', '00000246-0000-4000-8000-000000000902') ORDER BY id",
                UUID.class)).containsExactly(OWNER, SELLER);

        // client portal: the issued, paid and cancelled invoices with a phone (not the quote, the deleted one, the
        // one without phone)
        assertThat(jdbc.queryForObject("SELECT issued_at FROM public.invoice_phone_index WHERE invoice_id = "
                + "'00000246-0000-4000-8000-000000000102'", java.sql.Timestamp.class).toInstant())
                .isEqualTo(java.time.Instant.parse("2026-10-02T10:30:00Z")); // validatedAt, else createdAt
        assertThat(jdbc.queryForList("SELECT invoice_id::text || ':' || phone_normalized FROM "
                + "public.invoice_phone_index WHERE tenant_id = ? ORDER BY invoice_id", String.class, tenantId))
                .containsExactly("00000246-0000-4000-8000-000000000101:+237690024621",
                        "00000246-0000-4000-8000-000000000102:+237690024621",
                        "00000246-0000-4000-8000-000000000103:+237690024621");

        // provisioning: the AI module's tables exist; the Bref functions are not owner-disabled
        assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                schema + ".ai_conversations")).isTrue();
        assertThat(number("SELECT count(*) FROM public.tenant_module_activation WHERE tenant_id = ? AND module_id IN "
                + "('whatsapp-cloud', 'ai-assistant', 'client-portal') AND NOT activated", tenantId)).isZero();

        // audit: one TENANT_IMPORTED entry, {source: bref, counts}
        Map<String, Object> audit = jdbc.queryForMap("SELECT user_id, entity_type, entity_id, source, "
                + "metadata::text AS metadata FROM " + q + "audit_log WHERE action = 'TENANT_IMPORTED'");
        assertThat(audit.get("user_id")).isEqualTo(adminId);
        assertThat(audit.get("entity_id")).isEqualTo(tenantId);
        assertThat(audit.get("source")).isEqualTo("SYSTEM");
        JsonNode meta = objectMapper.readTree((String) audit.get("metadata"));
        assertThat(meta.path("source").asText()).isEqualTo("bref");
        assertThat(meta.path("counts").path("whatsappMessagesImported").asInt()).isEqualTo(4);

        // ── re-run: nothing written
        JsonNode again = data(importOwner(OWNER, "DATABASE"));
        assertThat(again.path("status").asText()).as(again.toString()).isEqualTo("COMPLETED");
        for (String key : List.of("whatsappAccountsImported", "whatsappTemplatesImported", "whatsappCampaignsImported",
                "whatsappRecipientsImported", "whatsappMessagesImported", "aiConversationsImported",
                "notificationsImported", "webPushSubscriptionsImported", "portalInvoicesIndexed")) {
            assertThat(again.path("counts").path(key).asInt()).as(key).isZero();
        }
        assertThat(number("SELECT count(*) FROM " + q + "audit_log WHERE action = 'TENANT_IMPORTED'")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM public.invoice_phone_index WHERE tenant_id = ?", tenantId))
                .isEqualTo(3);

        // ── the export gives the same section (same checksums) and imports nothing new
        writeExport(OWNER);
        List<UUID> accounts = List.of(OWNER, SELLER);
        BrefAnnexes fromDb = source(SourceKind.DATABASE).load(OWNER, accounts);
        BrefAnnexes fromExport = source(SourceKind.EXPORT).load(OWNER, accounts);
        assertThat(fromExport.whatsappAccount().canonical()).isEqualTo(fromDb.whatsappAccount().canonical());
        assertThat(fromExport.whatsappAccount().accessToken()).isEqualTo(TOKEN);
        same(fromExport.templates(), fromDb.templates(), BrefAnnexes.Template::canonical);
        same(fromExport.campaigns(), fromDb.campaigns(), BrefAnnexes.Campaign::canonical);
        same(fromExport.campaignMessages(), fromDb.campaignMessages(), BrefAnnexes.CampaignMessage::canonical);
        same(fromExport.chatMessages(), fromDb.chatMessages(), BrefAnnexes.ChatMessage::canonical);
        same(fromExport.aiConversations(), fromDb.aiConversations(), BrefAnnexes.AiConversation::canonical);
        same(fromExport.notifications(), fromDb.notifications(), BrefAnnexes.Notification::canonical);
        same(fromExport.webPushSubscriptions(), fromDb.webPushSubscriptions(),
                BrefAnnexes.WebPushSubscription::canonical);
        assertThat(fromDb.campaignMessages()).hasSize(3); // the platform campaign's row is not the owner's
        assertThat(fromDb.chatMessages()).hasSize(5); // the platform number's row is not the owner's
        JsonNode viaExport = data(importOwner(OWNER, "EXPORT"));
        assertThat(viaExport.path("status").asText()).as(viaExport.toString()).isEqualTo("COMPLETED");
        assertThat(viaExport.path("counts").path("whatsappMessagesImported").asInt()).isZero();
        assertThat(viaExport.path("counts").path("notificationsImported").asInt()).isZero();
    }

    private static <T> void same(List<T> a, List<T> b, Function<T, String> canonical) {
        assertThat(a.stream().map(canonical).toList()).isEqualTo(b.stream().map(canonical).toList());
    }

    @Test
    @DisplayName("a WhatsApp number or a browser endpoint already held by another company is not moved (noted, not "
            + "recorded); a number left by a deleted company is taken")
    void numberHeldElsewhere() throws Exception {
        String number = Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L));
        String endpoint = "https://push.example.invalid/send/" + UUID.randomUUID();
        UUID first = owner(number, endpoint);
        JsonNode one = data(importOwner(first, "DATABASE"));
        assertThat(one.path("status").asText()).as(one.toString()).isEqualTo("COMPLETED");
        assertThat(one.path("counts").path("whatsappAccountsImported").asInt()).isEqualTo(1);

        // the second owner's browser is already subscribed in Keevo (Bref keeps endpoints unique on its side)
        String held = "https://push.example.invalid/send/" + UUID.randomUUID();
        jdbc.update("INSERT INTO public.web_push_subscriptions (id, user_id, endpoint, p256dh, auth) VALUES (?, ?, ?, "
                + "'p256', 'auth')", UUID.randomUUID(), UUID.randomUUID(), held);
        UUID second = owner(number, held);
        JsonNode two = data(importOwner(second, "DATABASE"));
        assertThat(two.path("status").asText()).as(two.toString()).isEqualTo("COMPLETED");
        assertThat(two.path("counts").path("whatsappAccountsImported").asInt()).isZero();
        assertThat(two.path("notes").toString()).contains("WHATSAPP_NUMBER_HELD", "WEB_PUSH_ENDPOINT_HELD");
        assertThat(two.path("counts").path("webPushSubscriptionsImported").asInt()).isZero();
        UUID tenant = UUID.fromString(two.path("tenantId").asText());
        assertThat(number("SELECT count(*) FROM public.whatsapp_accounts WHERE tenant_id = ?", tenant)).isZero();
        assertThat(number("SELECT count(*) FROM public.whatsapp_accounts WHERE phone_number_id = ?", number))
                .isEqualTo(1);
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class,
                tenant);
        assertThat(number("SELECT count(*) FROM \"" + schema + "\".whatsapp_templates"))
                .as("no system template without an account").isZero();
        assertThat(number("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ? AND target_table IN "
                + "('whatsapp_accounts', 'web_push_subscriptions')", second)).isZero();

        // the first company deleted: its number is free for the next owner
        UUID firstTenant = UUID.fromString(one.path("tenantId").asText());
        jdbc.update("UPDATE public.tenants SET status = 'DELETED' WHERE id = ?", firstTenant);
        UUID third = owner(number, "https://push.example.invalid/send/" + UUID.randomUUID());
        JsonNode three = data(importOwner(third, "DATABASE"));
        assertThat(three.path("status").asText()).as(three.toString()).isEqualTo("COMPLETED");
        assertThat(three.path("counts").path("whatsappAccountsImported").asInt()).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM public.whatsapp_accounts WHERE tenant_id = ?",
                UUID.fromString(three.path("tenantId").asText()))).isEqualTo(1);
    }

    /** An owner with one shop, a connected WABA on {@code phoneNumberId} and a browser subscription. */
    private static UUID owner(String phoneNumberId, String endpoint) {
        UUID id = UUID.randomUUID();
        LocalDateTime at = LocalDateTime.of(2026, 9, 1, 8, 0);
        String phone = "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        bref.update("INSERT INTO \"user\" (id, phone, role, \"isActive\", \"isVerified\", \"createdAt\", "
                + "\"updatedAt\") VALUES (?, ?, 'owner', true, true, ?, ?)", id, phone, at, at);
        bref.update("INSERT INTO shop (id, \"userId\", name, address, category, country, \"createdAt\", \"updatedAt\") "
                + "VALUES (?, ?, ?, 'Adresse', 'electronics', 'CM', ?, ?)", UUID.randomUUID(), id,
                "Boutique " + id.toString().substring(0, 8), at, at);
        bref.update("INSERT INTO whatsapp_account (id, \"ownerId\", \"wabaId\", \"phoneNumberId\", \"accessToken\", "
                + "status, \"createdAt\", \"updatedAt\") VALUES (?, ?, '1234', ?, 'EAAG-synthetic', 'connected', ?, ?)",
                UUID.randomUUID(), id, phoneNumberId, at, at);
        bref.update("INSERT INTO web_push_subscription (id, \"userId\", endpoint, p256dh, auth, \"createdAt\", "
                + "\"updatedAt\") VALUES (?, ?, ?, 'p256', 'auth', ?, ?)", UUID.randomUUID(), id.toString(), endpoint,
                at, at);
        return id;
    }

    // ── export of an owner (24.2 identity sections + the 24.6 sections, from the synthetic Bref rows) ─────────

    private void writeExport(UUID owner) throws Exception {
        ObjectNode export = objectMapper.createObjectNode();
        export.put("formatVersion", 1);
        export.set("owner", read(bref.queryForObject("SELECT to_jsonb(u)::text FROM \"user\" u WHERE id = ?",
                String.class, owner)));
        section(export, "shops", "SELECT to_jsonb(s)::text FROM shop s WHERE \"userId\" = ?", owner);
        section(export, "members", "SELECT to_jsonb(u)::text FROM \"user\" u WHERE \"shopId\" IN (SELECT id::text "
                + "FROM shop WHERE \"userId\" = ?)", owner);
        export.set("whatsappAccount", read(bref.queryForObject("SELECT to_jsonb(a)::text FROM whatsapp_account a "
                + "WHERE \"ownerId\" = ?", String.class, owner)));
        section(export, "whatsappTemplates", "SELECT to_jsonb(t)::text FROM owner_whatsapp_template t "
                + "WHERE \"ownerId\" = ?", owner);
        section(export, "customerCampaigns", "SELECT to_jsonb(c)::text FROM customer_campaign c WHERE \"ownerId\" = ?",
                owner);
        section(export, "campaignMessages", "SELECT (to_jsonb(m) - 'campaignId' - 'userId')::text FROM "
                + "whatsapp_message m JOIN customer_campaign c ON c.id = m.\"customerCampaignId\" "
                + "WHERE c.\"ownerId\" = ?", owner);
        section(export, "whatsappChatMessages", "SELECT to_jsonb(m)::text FROM whatsapp_chat_message m "
                + "WHERE \"ownerId\" = ?", owner);
        // JavaScript-style rows: keys in another order, numbers written differently (1.0 → 1): same canonical text
        ArrayNode ai = export.putArray("aiConversations");
        bref.queryForList("SELECT to_jsonb(a)::text FROM ai_conversation a", String.class)
                .forEach(r -> ai.add(read(r)));
        ArrayNode notifications = export.putArray("notifications");
        bref.queryForList("SELECT replace(to_jsonb(n)::text, '5000.0', '5000') FROM notification n", String.class)
                .forEach(r -> notifications.add(read(r)));
        ArrayNode browsers = export.putArray("webPushSubscriptions");
        bref.queryForList("SELECT to_jsonb(w)::text FROM web_push_subscription w", String.class)
                .forEach(r -> browsers.add(read(r)));
        byte[] bytes = objectMapper.writeValueAsBytes(export);
        Files.write(EXPORT_DIR.resolve("owner-" + owner + ".json"), bytes);
        Files.writeString(EXPORT_DIR.resolve("owner-" + owner + ".json.sha256"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private void section(ObjectNode export, String name, String sql, UUID owner) {
        ArrayNode rows = export.putArray(name);
        bref.queryForList(sql, String.class, owner).forEach(r -> rows.add(read(r)));
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
