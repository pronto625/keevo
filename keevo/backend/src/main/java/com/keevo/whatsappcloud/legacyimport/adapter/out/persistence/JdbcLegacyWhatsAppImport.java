package com.keevo.whatsappcloud.legacyimport.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
import com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort;
import com.keevo.whatsappcloud.template.domain.model.SystemTemplates;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Story 24.6 — {@link LegacyWhatsAppImportPort} as set-based SQL: one {@code INSERT … SELECT FROM unnest(…) ON CONFLICT
 * DO NOTHING RETURNING} per table on the bound tenant's schema, the account in {@code public.whatsapp_accounts}. Rows are
 * checked against the tables' rules first (V68, module scripts V1–V3), so a bad row fails before any write. The access
 * token is encrypted with {@link SecretCipher} under the company id (the context {@code WhatsAppAccountService} reads
 * it with) and never logged.
 */
@Component
class JdbcLegacyWhatsAppImport implements LegacyWhatsAppImportPort {

    private static final Pattern META_ID = Pattern.compile("^[0-9]{1,64}$");
    private static final Pattern PHONE = Pattern.compile("^[0-9]{6,20}$");
    private static final Pattern TEMPLATE_NAME = Pattern.compile("^[a-z0-9_]+$");
    private static final Set<String> TEMPLATE_STATUSES = Set.of("DRAFT", "PENDING", "APPROVED", "REJECTED", "PAUSED",
            "DISABLED");
    private static final Set<String> CATEGORIES = Set.of("MARKETING", "UTILITY");
    private static final Set<String> HEADERS = Set.of("NONE", "TEXT", "IMAGE", "VIDEO", "DOCUMENT");
    private static final Set<String> CAMPAIGN_STATUSES = Set.of("DRAFT", "SENT", "CANCELLED");
    private static final Set<String> SOURCES = Set.of("STATIC", "CLIENT_NAME", "STORE_NAME");
    private static final Set<String> RECIPIENT_STATUSES = Set.of("SENT", "FAILED", "UNCERTAIN");
    private static final Set<String> MESSAGE_STATUSES = Set.of("SENT", "DELIVERED", "READ", "FAILED");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    JdbcLegacyWhatsAppImport(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public boolean canStoreTokens() {
        return cipher.isAvailable();
    }

    @Override
    public AccountOutcome importAccount(UUID tenantId, ImportedAccount a) {
        require(tenantId != null, "tenant");
        require(META_ID.matcher(a.wabaId()).matches() && META_ID.matcher(a.phoneNumberId()).matches(), "Meta ids");
        require(!a.accessToken().isBlank(), "token");
        require(max(a.displayPhoneNumber(), 32) && max(a.verifiedName(), 200), "account fields");
        if (!cipher.isAvailable()) {
            throw new IllegalStateException("No encryption key: the WhatsApp token cannot be stored");
        }
        Integer present = jdbc.queryForObject("SELECT CASE WHEN EXISTS (SELECT 1 FROM public.whatsapp_accounts "
                + "WHERE tenant_id = ?) THEN 1 WHEN EXISTS (SELECT 1 FROM public.whatsapp_accounts w JOIN "
                + "public.tenants t ON t.id = w.tenant_id WHERE w.phone_number_id = ? AND t.status <> 'DELETED') "
                + "THEN 2 ELSE 0 END", Integer.class, tenantId, a.phoneNumberId());
        if (present != null && present == 1) {
            return AccountOutcome.ALREADY_PRESENT;
        }
        if (present != null && present == 2) {
            return AccountOutcome.NUMBER_HELD;
        }
        // a number left behind by a deleted company never blocks it (21.5 rule)
        jdbc.update("DELETE FROM public.whatsapp_accounts a USING public.tenants t WHERE t.id = a.tenant_id "
                + "AND a.phone_number_id = ? AND t.status = 'DELETED'", a.phoneNumberId());
        byte[] token = cipher.encrypt(a.accessToken(), tenantId.toString());
        Timestamp at = Timestamp.from(a.connectedAt());
        int n = jdbc.update("INSERT INTO public.whatsapp_accounts (tenant_id, waba_id, phone_number_id, "
                        + "display_phone_number, verified_name, access_token_enc, connected_by, connected_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                tenantId, a.wabaId(), a.phoneNumberId(), a.displayPhoneNumber(), a.verifiedName(), token,
                a.connectedBy(), at, at);
        if (n == 1) {
            return AccountOutcome.INSERTED;
        }
        // lost a race (the company or the number was connected meanwhile): tell which
        Integer mine = jdbc.queryForObject("SELECT count(*) FROM public.whatsapp_accounts WHERE tenant_id = ?",
                Integer.class, tenantId);
        return mine != null && mine > 0 ? AccountOutcome.ALREADY_PRESENT : AccountOutcome.NUMBER_HELD;
    }

    @Override
    public void ensureSystemTemplates(String tenantSchema, UUID createdBy, Instant at) {
        String table = table(tenantSchema, "whatsapp_templates");
        UUID id = UUID.nameUUIDFromBytes(("bref-system-template:" + tenantSchema + ":" + SystemTemplates.INVOICE_NAME)
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO " + table + " (id, name, language, category, "
                    + "header_format, body_text, body_examples, footer_text, status, system, created_by, created_at, "
                    + "updated_at) VALUES (?, ?, ?, ?, 'DOCUMENT', ?, ?, ?, 'PENDING', true, ?, ?, ?) "
                    + "ON CONFLICT DO NOTHING");
            ps.setObject(1, id);
            ps.setString(2, SystemTemplates.INVOICE_NAME);
            ps.setString(3, SystemTemplates.INVOICE_LANGUAGE);
            ps.setString(4, SystemTemplates.INVOICE_CATEGORY);
            ps.setString(5, SystemTemplates.INVOICE_BODY);
            ps.setArray(6, con.createArrayOf("text", SystemTemplates.INVOICE_EXAMPLES.toArray()));
            ps.setString(7, SystemTemplates.INVOICE_FOOTER);
            ps.setObject(8, createdBy);
            ps.setTimestamp(9, Timestamp.from(at));
            ps.setTimestamp(10, Timestamp.from(at));
            return ps;
        });
    }

    @Override
    public Written importTemplates(String tenantSchema, List<ImportedTemplate> templates) {
        String table = table(tenantSchema, "whatsapp_templates");
        if (templates.isEmpty()) {
            return new Written(Set.of(), Set.of());
        }
        Set<UUID> ids = new HashSet<>();
        for (ImportedTemplate t : templates) {
            require(ids.add(t.id()), "duplicate template");
            require(t.name() != null && t.name().length() <= 512 && TEMPLATE_NAME.matcher(t.name()).matches(),
                    "template name");
            require(t.language() != null && t.language().length() <= 10, "template language");
            require(CATEGORIES.contains(t.category()) && HEADERS.contains(t.headerFormat())
                    && TEMPLATE_STATUSES.contains(t.status()), "template codes");
            require(t.bodyText() != null && max(t.bodyText(), 1024) && max(t.headerText(), 60)
                    && max(t.footerText(), 60) && max(t.rejectedReason(), 200) && max(t.submitError(), 500)
                    && max(t.metaTemplateId(), 64), "template fields");
            require(t.createdAt() != null && t.updatedAt() != null, "template dates");
        }
        String sql = "WITH ins AS (INSERT INTO " + table + " (id, name, language, category, header_format, header_text, "
                + "body_text, body_examples, footer_text, status, rejected_reason, submit_error, meta_template_id, "
                + "system, created_by, created_at, updated_at) "
                + "SELECT x.id, x.name, x.language, x.category, x.header_format, x.header_text, x.body_text, "
                + "COALESCE(ARRAY(SELECT jsonb_array_elements_text(x.examples::jsonb)), '{}'), x.footer_text, "
                + "x.status, x.rejected_reason, x.submit_error, x.meta_template_id, false, x.created_by, "
                + "x.created_at::timestamptz, x.updated_at::timestamptz "
                + "FROM unnest(?::uuid[], ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], "
                + "?::varchar[], ?::text[], ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], "
                + "?::uuid[], ?::text[], ?::text[]) AS x(id, name, language, category, header_format, header_text, "
                + "body_text, examples, footer_text, status, rejected_reason, submit_error, meta_template_id, "
                + "created_by, created_at, updated_at) ON CONFLICT DO NOTHING RETURNING id) " + PRESENT_TAIL
                .replace("{table}", table);
        return written(sql, templates, ImportedTemplate::id, List.of(
                col("uuid", ImportedTemplate::id), col("varchar", ImportedTemplate::name),
                col("varchar", ImportedTemplate::language), col("varchar", ImportedTemplate::category),
                col("varchar", ImportedTemplate::headerFormat), col("varchar", ImportedTemplate::headerText),
                col("varchar", ImportedTemplate::bodyText), col("text", t -> json(t.bodyExamples())),
                col("varchar", ImportedTemplate::footerText), col("varchar", ImportedTemplate::status),
                col("varchar", ImportedTemplate::rejectedReason), col("varchar", ImportedTemplate::submitError),
                col("varchar", ImportedTemplate::metaTemplateId), col("uuid", ImportedTemplate::createdBy),
                col("text", t -> text(t.createdAt())), col("text", t -> text(t.updatedAt()))));
    }

    /**
     * After {@code WITH ins AS (INSERT … RETURNING id)}: the inserted ids (flag true), then the given ids that were
     * already there (the outer query sees the table as before the statement, so an inserted row is never listed twice).
     */
    private static final String PRESENT_TAIL = "SELECT id, true AS inserted FROM ins UNION ALL "
            + "SELECT t.id, false FROM {table} t WHERE t.id = ANY (?::uuid[])";

    /** Runs a {@code WITH ins … PRESENT_TAIL} statement: the column arrays, then the given ids. */
    private <T> Written written(String sql, List<T> rows, Function<T, UUID> id, List<Col<T>> cols) {
        Set<UUID> inserted = new LinkedHashSet<>();
        Set<UUID> present = new LinkedHashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = statement(con, sql, rows, cols);
            ps.setArray(cols.size() + 1, con.createArrayOf("uuid", rows.stream().map(id).toArray()));
            return ps;
        }, rs -> {
            UUID x = rs.getObject(1, UUID.class);
            present.add(x);
            if (rs.getBoolean(2)) {
                inserted.add(x);
            }
        });
        return new Written(inserted, present);
    }

    @Override
    public Set<UUID> importCampaigns(String tenantSchema, List<ImportedCampaign> campaigns) {
        String table = table(tenantSchema, "whatsapp_campaigns");
        if (campaigns.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (ImportedCampaign c : campaigns) {
            require(ids.add(c.id()), "duplicate campaign");
            require(c.name() != null && !c.name().isBlank() && max(c.name(), 100) && max(c.description(), 500),
                    "campaign name");
            require(CAMPAIGN_STATUSES.contains(c.status()), "campaign status");
            require(max(c.headerMediaLink(), 2048) && max(c.templateName(), 512) && max(c.templateLanguage(), 10)
                    && max(c.templateBody(), 1024) && (c.templateHeader() == null || HEADERS.contains(c.templateHeader())),
                    "campaign template");
            require(c.variables().stream().allMatch(v -> SOURCES.contains(v.source()) && max(v.value(), 1024)),
                    "campaign variables");
            require(in(c.inactiveForDays(), 1, 3650) && in(c.purchasedWithinDays(), 1, 3650)
                    && in(c.totalSpentMin(), 0, 1_000_000_000_000L) && in(c.totalSpentMax(), 0, 1_000_000_000_000L)
                    && in(c.totalInvoicesMin(), 0, 10_000_000) && in(c.totalInvoicesMax(), 0, 10_000_000),
                    "campaign criteria");
            require(c.targetedCount() >= 0 && c.sentCount() >= 0 && c.deliveredCount() >= 0 && c.readCount() >= 0
                    && c.failedCount() >= 0, "campaign counters");
            require(c.createdAt() != null && c.updatedAt() != null, "campaign dates");
        }
        String sql = "INSERT INTO " + table + " (id, name, description, status, store_ids, template_id, variables, "
                + "header_media_link, template_name, template_language, template_body, template_header, started_at, "
                + "sent_at, targeted_count, sent_count, delivered_count, read_count, failed_count, inactive_for_days, "
                + "purchased_within_days, total_spent_min, total_spent_max, total_invoices_min, total_invoices_max, "
                + "created_by, created_at, updated_at) "
                + "SELECT x.id, x.name, x.description, x.status, "
                + "COALESCE(ARRAY(SELECT jsonb_array_elements_text(x.stores::jsonb)::uuid), '{}'), x.template_id, "
                + "x.variables::jsonb, x.link, x.t_name, x.t_language, x.t_body, x.t_header, "
                + "x.started_at::timestamptz, x.sent_at::timestamptz, x.targeted, x.sent, x.delivered, x.read, "
                + "x.failed, x.inactive, x.within, x.spent_min, x.spent_max, x.inv_min, x.inv_max, x.created_by, "
                + "x.created_at::timestamptz, x.updated_at::timestamptz "
                + "FROM unnest(?::uuid[], ?::varchar[], ?::varchar[], ?::varchar[], ?::text[], ?::uuid[], ?::text[], "
                + "?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], ?::text[], ?::text[], "
                + "?::int[], ?::int[], ?::int[], ?::int[], ?::int[], ?::int[], ?::int[], ?::bigint[], ?::bigint[], "
                + "?::int[], ?::int[], ?::uuid[], ?::text[], ?::text[]) AS x(id, name, description, status, stores, "
                + "template_id, variables, link, t_name, t_language, t_body, t_header, started_at, sent_at, targeted, "
                + "sent, delivered, read, failed, inactive, within, spent_min, spent_max, inv_min, inv_max, "
                + "created_by, created_at, updated_at) ON CONFLICT DO NOTHING RETURNING id";
        return insert(sql, campaigns, List.of(
                col("uuid", ImportedCampaign::id), col("varchar", ImportedCampaign::name),
                col("varchar", ImportedCampaign::description), col("varchar", ImportedCampaign::status),
                col("text", c -> json(c.storeIds().stream().map(UUID::toString).toList())),
                col("uuid", ImportedCampaign::templateId), col("text", c -> variables(c.variables())),
                col("varchar", ImportedCampaign::headerMediaLink), col("varchar", ImportedCampaign::templateName),
                col("varchar", ImportedCampaign::templateLanguage), col("varchar", ImportedCampaign::templateBody),
                col("varchar", ImportedCampaign::templateHeader), col("text", c -> text(c.startedAt())),
                col("text", c -> text(c.sentAt())), col("int4", ImportedCampaign::targetedCount),
                col("int4", ImportedCampaign::sentCount), col("int4", ImportedCampaign::deliveredCount),
                col("int4", ImportedCampaign::readCount), col("int4", ImportedCampaign::failedCount),
                col("int4", ImportedCampaign::inactiveForDays), col("int4", ImportedCampaign::purchasedWithinDays),
                col("int8", ImportedCampaign::totalSpentMin), col("int8", ImportedCampaign::totalSpentMax),
                col("int4", ImportedCampaign::totalInvoicesMin), col("int4", ImportedCampaign::totalInvoicesMax),
                col("uuid", ImportedCampaign::createdBy), col("text", c -> text(c.createdAt())),
                col("text", c -> text(c.updatedAt()))));
    }

    @Override
    public int importRecipients(String tenantSchema, List<ImportedRecipient> recipients) {
        String table = table(tenantSchema, "whatsapp_campaign_recipients");
        if (recipients.isEmpty()) {
            return 0;
        }
        Set<String> keys = new HashSet<>();
        for (ImportedRecipient r : recipients) {
            require(r.campaignId() != null && r.clientId() != null && r.updatedAt() != null, "recipient ids");
            require(r.phone() != null && PHONE.matcher(r.phone()).matches(), "recipient phone");
            require(keys.add(r.campaignId() + ":" + r.phone()), "duplicate recipient");
            require(RECIPIENT_STATUSES.contains(r.status()) && max(r.clientName(), 200) && max(r.storeName(), 200)
                    && max(r.waMessageId(), 128) && max(r.error(), 200), "recipient fields");
        }
        String sql = "INSERT INTO " + table + " (campaign_id, phone, client_id, client_name, store_name, status, "
                + "wa_message_id, error, updated_at) SELECT x.campaign_id, x.phone, x.client_id, x.client_name, "
                + "x.store_name, x.status, x.wa_id, x.error, x.updated_at::timestamptz "
                + "FROM unnest(?::uuid[], ?::varchar[], ?::uuid[], ?::varchar[], ?::varchar[], ?::varchar[], "
                + "?::varchar[], ?::varchar[], ?::text[]) AS x(campaign_id, phone, client_id, client_name, store_name, "
                + "status, wa_id, error, updated_at) ON CONFLICT DO NOTHING RETURNING campaign_id";
        List<Object> inserted = new ArrayList<>();
        run(sql, recipients, List.of(
                col("uuid", ImportedRecipient::campaignId), col("varchar", ImportedRecipient::phone),
                col("uuid", ImportedRecipient::clientId), col("varchar", ImportedRecipient::clientName),
                col("varchar", ImportedRecipient::storeName), col("varchar", ImportedRecipient::status),
                col("varchar", ImportedRecipient::waMessageId), col("varchar", ImportedRecipient::error),
                col("text", r -> text(r.updatedAt()))), inserted::add);
        return inserted.size();
    }

    @Override
    public Written importMessages(String tenantSchema, List<ImportedMessage> messages,
                                 Map<String, String> contactNames) {
        String table = table(tenantSchema, "whatsapp_messages");
        String conversations = table(tenantSchema, "whatsapp_conversations");
        if (messages.isEmpty()) {
            return new Written(Set.of(), Set.of());
        }
        Set<UUID> ids = new HashSet<>();
        Set<String> waIds = new HashSet<>();
        for (ImportedMessage m : messages) {
            require(ids.add(m.id()), "duplicate message");
            require(m.contactPhone() != null && PHONE.matcher(m.contactPhone()).matches(), "message phone");
            require("IN".equals(m.direction()) || "OUT".equals(m.direction()), "message direction");
            require(m.type() != null && !m.type().isBlank() && max(m.type(), 20) && max(m.body(), 8192),
                    "message content");
            require(m.waMessageId() != null && max(m.waMessageId(), 128) && waIds.add(m.waMessageId()),
                    "message Meta id");
            require(m.status() == null || ("OUT".equals(m.direction()) && MESSAGE_STATUSES.contains(m.status())),
                    "message status");
            require(m.createdAt() != null, "message date");
        }
        String sql = "WITH ins AS (INSERT INTO " + table + " (id, contact_phone, direction, type, body, wa_message_id, status, "
                + "read_at, sent_by, created_at) SELECT x.id, x.phone, x.direction, x.type, x.body, x.wa_id, x.status, "
                + "x.read_at::timestamptz, x.sent_by, x.created_at::timestamptz "
                + "FROM unnest(?::uuid[], ?::varchar[], ?::varchar[], ?::varchar[], ?::text[], ?::varchar[], "
                + "?::varchar[], ?::text[], ?::uuid[], ?::text[]) AS x(id, phone, direction, type, body, wa_id, "
                + "status, read_at, sent_by, created_at) ON CONFLICT DO NOTHING RETURNING id) " + PRESENT_TAIL
                .replace("{table}", table);
        Written written = written(sql, messages, ImportedMessage::id, List.of(
                col("uuid", ImportedMessage::id), col("varchar", ImportedMessage::contactPhone),
                col("varchar", ImportedMessage::direction), col("varchar", ImportedMessage::type),
                col("text", ImportedMessage::body), col("varchar", ImportedMessage::waMessageId),
                col("varchar", ImportedMessage::status), col("text", m -> text(m.readAt())),
                col("uuid", ImportedMessage::sentBy), col("text", m -> text(m.createdAt()))));
        Set<String> touched = new LinkedHashSet<>();
        messages.stream().filter(m -> written.inserted().contains(m.id())).forEach(m -> touched.add(m.contactPhone()));
        if (!touched.isEmpty()) {
            rebuildConversations(table, conversations, touched, contactNames);
        }
        return written;
    }

    /**
     * One statement for every touched contact: the latest message (thread index), the unread inbound count and the
     * last inbound instant, upserted into the conversation row (the name kept when Bref knew none).
     */
    private void rebuildConversations(String messages, String conversations, Set<String> phones,
                                      Map<String, String> names) {
        Object[] phoneArray = phones.toArray();
        Object[] nameArray = phones.stream().map(p -> bounded(names.get(p), 200)).toArray();
        String sql = "INSERT INTO " + conversations + " (contact_phone, contact_name, last_message, last_type, "
                + "last_direction, last_at, unread_count, last_inbound_at) "
                + "SELECT c.phone, c.name, left(l.body, 500), l.type, l.direction, l.created_at, a.unread, a.last_in "
                + "FROM unnest(?::varchar[], ?::varchar[]) AS c(phone, name) "
                + "CROSS JOIN LATERAL (SELECT m.body, m.type, m.direction, m.created_at FROM " + messages + " m "
                + "WHERE m.contact_phone = c.phone ORDER BY m.created_at DESC, m.id DESC LIMIT 1) l "
                + "CROSS JOIN LATERAL (SELECT count(*) FILTER (WHERE m.direction = 'IN' AND m.read_at IS NULL)::int "
                + "AS unread, max(m.created_at) FILTER (WHERE m.direction = 'IN') AS last_in FROM " + messages + " m "
                + "WHERE m.contact_phone = c.phone) a "
                + "ON CONFLICT (contact_phone) DO UPDATE SET contact_name = COALESCE(EXCLUDED.contact_name, "
                + "whatsapp_conversations.contact_name), last_message = EXCLUDED.last_message, "
                + "last_type = EXCLUDED.last_type, last_direction = EXCLUDED.last_direction, "
                + "last_at = EXCLUDED.last_at, unread_count = EXCLUDED.unread_count, "
                + "last_inbound_at = EXCLUDED.last_inbound_at";
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("varchar", phoneArray));
            ps.setArray(2, con.createArrayOf("varchar", nameArray));
            return ps;
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String table(String tenantSchema, String name) {
        TenantContext.requireCurrent(tenantSchema);
        String schema = TenantSchema.validate(tenantSchema);
        return "\"" + schema + "\"." + name;
    }

    private record Col<T>(String type, Function<T, Object> value) {
    }

    private static <T> Col<T> col(String type, Function<T, Object> value) {
        return new Col<>(type, value);
    }

    private <T> Set<UUID> insert(String sql, List<T> rows, List<Col<T>> cols) {
        Set<UUID> inserted = new LinkedHashSet<>();
        run(sql, rows, cols, id -> inserted.add((UUID) id));
        return inserted;
    }

    private <T> void run(String sql, List<T> rows, List<Col<T>> cols, java.util.function.Consumer<Object> each) {
        jdbc.query(con -> statement(con, sql, rows, cols), rs -> {
            each.accept(rs.getObject(1));
        });
    }

    private static <T> PreparedStatement statement(Connection con, String sql, List<T> rows, List<Col<T>> cols)
            throws SQLException {
        PreparedStatement ps = con.prepareStatement(sql);
        for (int i = 0; i < cols.size(); i++) {
            Col<T> c = cols.get(i);
            ps.setArray(i + 1, con.createArrayOf(c.type(), rows.stream().map(c.value()).toArray()));
        }
        return ps;
    }

    private static String variables(List<Variable> variables) {
        List<Map<String, String>> list = new ArrayList<>();
        for (Variable v : variables) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("source", v.source());
            if (v.value() != null) {
                m.put("value", v.value());
            }
            list.add(m);
        }
        return json(list);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("not serializable", e);
        }
    }

    private static String text(Instant at) {
        return at == null ? null : at.toString();
    }

    private static boolean max(String value, int length) {
        return value == null || value.codePointCount(0, value.length()) <= length;
    }

    private static String bounded(String value, int length) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        return v.codePointCount(0, v.length()) <= length ? v : v.substring(0, v.offsetByCodePoints(0, length));
    }

    private static boolean in(Number value, long min, long maxValue) {
        return value == null || (value.longValue() >= min && value.longValue() <= maxValue);
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("invalid imported WhatsApp row: " + what);
        }
    }
}
