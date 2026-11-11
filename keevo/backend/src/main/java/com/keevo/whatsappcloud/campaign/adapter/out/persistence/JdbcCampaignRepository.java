package com.keevo.whatsappcloud.campaign.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.whatsappcloud.campaign.domain.model.Campaign;
import com.keevo.whatsappcloud.campaign.domain.model.CampaignStatus;
import com.keevo.whatsappcloud.campaign.domain.model.PurchaseCriteria;
import com.keevo.whatsappcloud.campaign.domain.model.Recipient;
import com.keevo.whatsappcloud.campaign.domain.model.RecipientStatus;
import com.keevo.whatsappcloud.campaign.domain.model.VariableMapping;
import com.keevo.whatsappcloud.campaign.domain.model.VariableSource;
import com.keevo.whatsappcloud.campaign.domain.port.out.CampaignRepository;
import com.keevo.whatsappcloud.inbox.domain.model.StatusChange;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.6 — {@link CampaignRepository} on the module tables of the tenant schema, plain JDBC; every statement names
 * {@code "kv_xxxxxx".<table>} after {@link TenantSchema#validate}. Costs: list = index scan
 * {@code idx_whatsapp_campaigns_created} with LIMIT and one LEFT JOIN by template PK; claim = the partial index
 * {@code idx_whatsapp_campaign_recipients_pending} in index order with LIMIT and SKIP LOCKED; outcomes = one JDBC batch + one counter
 * UPDATE; recipients insert = one JDBC batch; status changes = one batch of counter UPDATEs (one per campaign).
 */
@Component
public class JdbcCampaignRepository implements CampaignRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, String>>> VARIABLES = new TypeReference<>() {
    };

    private static final String CAMPAIGN_COLUMNS = "c.id, c.name, c.description, c.status, c.store_ids, "
            + "c.template_id, c.variables::text AS variables, c.header_media_id, c.header_media_link, c.template_name, "
            + "c.template_language, c.template_body, c.template_header, c.scheduled_at, c.started_at, c.sent_at, "
            + "c.targeted_count, c.sent_count, c.delivered_count, c.read_count, c.failed_count, c.skipped_count, "
            + "c.uncertain_count, c.created_by, c.created_at, c.updated_at, t.name AS t_name, t.language AS t_language, "
            + "t.body_text AS t_body, t.header_format AS t_header, c.inactive_for_days, c.purchased_within_days, "
            + "c.total_spent_min, c.total_spent_max, c.total_invoices_min, c.total_invoices_max";

    private final JdbcTemplate jdbc;

    public JdbcCampaignRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String table(String schema, String table) {
        TenantSchema.validate(schema);
        return "\"" + schema + "\"." + table;
    }

    @Override
    public void insert(String schema, Campaign c) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO " + table(schema, "whatsapp_campaigns") + " (id, name, "
                    + "description, status, store_ids, template_id, variables, header_media_id, header_media_link, "
                    + "scheduled_at, targeted_count, created_by, created_at, updated_at, inactive_for_days, "
                    + "purchased_within_days, total_spent_min, total_spent_max, total_invoices_min, total_invoices_max) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setObject(1, c.id());
            ps.setString(2, c.name());
            ps.setString(3, c.description());
            ps.setString(4, c.status().name());
            ps.setArray(5, con.createArrayOf("uuid", c.storeIds().toArray()));
            ps.setObject(6, c.templateId());
            ps.setString(7, json(c.variables()));
            ps.setString(8, c.headerMediaId());
            ps.setString(9, c.headerMediaLink());
            ps.setTimestamp(10, ts(c.scheduledAt()));
            ps.setInt(11, c.counters().targeted());
            ps.setObject(12, c.createdBy());
            ps.setTimestamp(13, ts(c.createdAt()));
            ps.setTimestamp(14, ts(c.updatedAt()));
            setCriteria(ps, 15, c.criteria());
            return ps;
        });
    }

    @Override
    public boolean updateEditable(String schema, Campaign c) {
        return jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE " + table(schema, "whatsapp_campaigns") + " SET name = ?, "
                    + "description = ?, status = ?, store_ids = ?, template_id = ?, variables = ?::jsonb, "
                    + "header_media_id = ?, header_media_link = ?, scheduled_at = ?, targeted_count = ?, "
                    + "updated_at = ?, inactive_for_days = ?, purchased_within_days = ?, total_spent_min = ?, "
                    + "total_spent_max = ?, total_invoices_min = ?, total_invoices_max = ? "
                    + "WHERE id = ? AND status IN ('DRAFT', 'SCHEDULED')");
            ps.setString(1, c.name());
            ps.setString(2, c.description());
            ps.setString(3, c.status().name());
            ps.setArray(4, con.createArrayOf("uuid", c.storeIds().toArray()));
            ps.setObject(5, c.templateId());
            ps.setString(6, json(c.variables()));
            ps.setString(7, c.headerMediaId());
            ps.setString(8, c.headerMediaLink());
            ps.setTimestamp(9, ts(c.scheduledAt()));
            ps.setInt(10, c.counters().targeted());
            ps.setTimestamp(11, ts(c.updatedAt()));
            setCriteria(ps, 12, c.criteria());
            ps.setObject(18, c.id());
            return ps;
        }) == 1;
    }

    /** Story 21.11 — the six criteria columns from {@code first} (null = not set). */
    private static void setCriteria(java.sql.PreparedStatement ps, int first, PurchaseCriteria c) throws SQLException {
        ps.setObject(first, c.inactiveForDays(), java.sql.Types.INTEGER);
        ps.setObject(first + 1, c.purchasedWithinDays(), java.sql.Types.INTEGER);
        ps.setObject(first + 2, c.totalSpentMin(), java.sql.Types.BIGINT);
        ps.setObject(first + 3, c.totalSpentMax(), java.sql.Types.BIGINT);
        ps.setObject(first + 4, c.totalInvoicesMin(), java.sql.Types.INTEGER);
        ps.setObject(first + 5, c.totalInvoicesMax(), java.sql.Types.INTEGER);
    }

    @Override
    public boolean deleteUnsent(String schema, UUID id) {
        return jdbc.update("DELETE FROM " + table(schema, "whatsapp_campaigns") + " WHERE id = ? AND status IN "
                + "('DRAFT', 'SCHEDULED', 'CANCELLED')", id) == 1;
    }

    @Override
    public Optional<CampaignRow> find(String schema, UUID id) {
        return jdbc.query("SELECT " + CAMPAIGN_COLUMNS + " FROM " + table(schema, "whatsapp_campaigns") + " c "
                        + "LEFT JOIN " + table(schema, "whatsapp_templates") + " t ON t.id = c.template_id "
                        + "WHERE c.id = ?", ROW, id).stream().findFirst();
    }

    @Override
    public List<CampaignRow> page(String schema, Instant beforeCreatedAt, UUID beforeId, int limit) {
        String from = "SELECT " + CAMPAIGN_COLUMNS + " FROM " + table(schema, "whatsapp_campaigns") + " c "
                + "LEFT JOIN " + table(schema, "whatsapp_templates") + " t ON t.id = c.template_id";
        if (beforeCreatedAt == null) {
            return jdbc.query(from + " ORDER BY c.created_at DESC, c.id DESC LIMIT ?", ROW, limit);
        }
        if (beforeId == null) {
            return jdbc.query(from + " WHERE c.created_at < ? ORDER BY c.created_at DESC, c.id DESC LIMIT ?", ROW,
                    ts(beforeCreatedAt), limit);
        }
        return jdbc.query(from + " WHERE (c.created_at, c.id) < (?, ?) ORDER BY c.created_at DESC, c.id DESC LIMIT ?",
                ROW, ts(beforeCreatedAt), beforeId, limit);
    }

    @Override
    public boolean unschedule(String schema, UUID id, Instant now) {
        return jdbc.update("UPDATE " + table(schema, "whatsapp_campaigns") + " SET status = 'DRAFT', scheduled_at = NULL, "
                + "updated_at = ? WHERE id = ? AND status = 'SCHEDULED'", ts(now), id) == 1;
    }

    @Override
    public boolean cancel(String schema, UUID id, Instant now) {
        return jdbc.update("UPDATE " + table(schema, "whatsapp_campaigns") + " SET status = 'CANCELLED', "
                + "updated_at = ? WHERE id = ? AND status = 'SCHEDULED'", ts(now), id) == 1;
    }

    @Override
    public boolean start(String schema, UUID id, Campaign.FrozenTemplate t, int targeted, Instant now) {
        return jdbc.update("UPDATE " + table(schema, "whatsapp_campaigns") + " SET status = 'SENDING', "
                        + "template_name = ?, template_language = ?, template_body = ?, template_header = ?, "
                        + "targeted_count = ?, started_at = ?, updated_at = ? WHERE id = ? AND status IN "
                        + "('DRAFT', 'SCHEDULED')", t.name(), t.language(), t.bodyText(), t.headerFormat(), targeted,
                ts(now), ts(now), id) == 1;
    }

    @Override
    public void insertRecipients(String schema, UUID campaignId, List<Recipient> recipients) {
        if (recipients.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO " + table(schema, "whatsapp_campaign_recipients") + " (campaign_id, phone, "
                        + "client_id, client_name, store_name, status) VALUES (?, ?, ?, ?, ?, 'PENDING') "
                        + "ON CONFLICT (campaign_id, phone) DO NOTHING", recipients, 500, (ps, r) -> {
                    ps.setObject(1, campaignId);
                    ps.setString(2, r.phone());
                    ps.setObject(3, r.clientId());
                    ps.setString(4, r.clientName());
                    ps.setString(5, r.storeName());
                });
    }

    @Override
    public List<Recipient> claim(String schema, UUID campaignId, int limit, Instant now) {
        String recipients = table(schema, "whatsapp_campaign_recipients");
        return jdbc.query("UPDATE " + recipients + " r SET status = 'SENDING', updated_at = ? WHERE (r.campaign_id, "
                        + "r.phone) IN (SELECT campaign_id, phone FROM " + recipients + " WHERE campaign_id = ? AND "
                        + "status = 'PENDING' ORDER BY phone LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING r.phone, "
                        + "r.client_id, r.client_name, r.store_name",
                (rs, n) -> new Recipient(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), RecipientStatus.SENDING, null, null, null),
                ts(now), campaignId, limit);
    }

    @Override
    public int expireStuck(String schema, UUID campaignId, Instant before, Instant now) {
        String recipients = table(schema, "whatsapp_campaign_recipients");
        String campaigns = table(schema, "whatsapp_campaigns");
        Integer n = jdbc.queryForObject("WITH s AS (UPDATE " + recipients + " SET status = 'UNCERTAIN', error = ?, "
                        + "updated_at = ? WHERE campaign_id = ? AND status = 'SENDING' AND updated_at < ? RETURNING 1), "
                        + "c AS (UPDATE " + campaigns + " SET uncertain_count = uncertain_count + (SELECT count(*) FROM s) "
                        + "WHERE id = ? AND EXISTS (SELECT 1 FROM s) RETURNING 1) SELECT count(*)::int FROM s",
                Integer.class, "Envoi interrompu : message peut-être reçu", ts(now), campaignId, ts(before),
                campaignId);
        return n == null ? 0 : n;
    }

    @Override
    public void recordOutcomes(String schema, UUID campaignId, List<Outcome> outcomes, Instant now) {
        if (outcomes.isEmpty()) {
            return;
        }
        int[][] updated = jdbc.batchUpdate("UPDATE " + table(schema, "whatsapp_campaign_recipients") + " SET status = ?, "
                        + "wa_message_id = ?, error = ?, updated_at = ? WHERE campaign_id = ? AND phone = ? "
                        + "AND status = 'SENDING'", outcomes, outcomes.size(), (ps, o) -> {
                    ps.setString(1, o.status().name());
                    ps.setString(2, o.waMessageId());
                    ps.setString(3, o.error() == null ? null : o.error().length() > 200 ? o.error().substring(0, 200)
                            : o.error());
                    ps.setTimestamp(4, ts(now));
                    ps.setObject(5, campaignId);
                    ps.setString(6, o.phone());
                });
        // only the rows really moved count (a row already expired UNCERTAIN by another instance is not counted twice)
        List<Outcome> applied = new ArrayList<>();
        int i = 0;
        for (int[] batch : updated) {
            for (int c : batch) {
                if (c != 0) {
                    applied.add(outcomes.get(i));
                }
                i++;
            }
        }
        int sent = count(applied, RecipientStatus.SENT);
        int failed = count(applied, RecipientStatus.FAILED);
        int skipped = count(applied, RecipientStatus.SKIPPED);
        int uncertain = count(applied, RecipientStatus.UNCERTAIN);
        if (sent + failed + skipped + uncertain > 0) {
            jdbc.update("UPDATE " + table(schema, "whatsapp_campaigns") + " SET sent_count = sent_count + ?, "
                            + "failed_count = failed_count + ?, skipped_count = skipped_count + ?, "
                            + "uncertain_count = uncertain_count + ?, updated_at = ? WHERE id = ?",
                    sent, failed, skipped, uncertain, ts(now), campaignId);
        }
    }

    @Override
    public int skipPending(String schema, UUID campaignId, String reason, Instant now) {
        String recipients = table(schema, "whatsapp_campaign_recipients");
        String campaigns = table(schema, "whatsapp_campaigns");
        Integer n = jdbc.queryForObject("WITH s AS (UPDATE " + recipients + " SET status = 'SKIPPED', error = ?, "
                        + "updated_at = ? WHERE campaign_id = ? AND status = 'PENDING' RETURNING 1), "
                        + "c AS (UPDATE " + campaigns + " SET skipped_count = skipped_count + (SELECT count(*) FROM s) "
                        + "WHERE id = ? AND EXISTS (SELECT 1 FROM s) RETURNING 1) SELECT count(*)::int FROM s",
                Integer.class, reason, ts(now), campaignId, campaignId);
        return n == null ? 0 : n;
    }

    @Override
    public boolean hasOpenRecipients(String schema, UUID campaignId) {
        String recipients = table(schema, "whatsapp_campaign_recipients");
        // one probe on each partial index (pending, sending)
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + recipients
                + " WHERE campaign_id = ? AND status = 'PENDING') OR EXISTS (SELECT 1 FROM " + recipients
                + " WHERE campaign_id = ? AND status = 'SENDING')", Boolean.class, campaignId, campaignId));
    }

    @Override
    public boolean complete(String schema, UUID campaignId, Instant now) {
        return jdbc.update("UPDATE " + table(schema, "whatsapp_campaigns") + " SET status = 'SENT', sent_at = ?, "
                + "updated_at = ? WHERE id = ? AND status = 'SENDING'", ts(now), ts(now), campaignId) == 1;
    }

    @Override
    public void applyStatusChanges(String schema, List<StatusChange> changes) {
        Map<UUID, int[]> deltas = new LinkedHashMap<>();
        for (StatusChange ch : changes) {
            if (ch.campaignId() == null) {
                continue;
            }
            int[] d = deltas.computeIfAbsent(ch.campaignId(), k -> new int[4]);
            d[0] += ch.sentDelta();
            d[1] += ch.deliveredDelta();
            d[2] += ch.readDelta();
            d[3] += ch.failedDelta();
        }
        if (deltas.isEmpty()) {
            return;
        }
        List<Map.Entry<UUID, int[]>> rows = new ArrayList<>(deltas.entrySet());
        // plain sums (no clamp): a status that arrives before the batch's own bookkeeping still ends exact
        jdbc.batchUpdate("UPDATE " + table(schema, "whatsapp_campaigns") + " SET sent_count = sent_count + ?, "
                        + "delivered_count = delivered_count + ?, read_count = read_count + ?, "
                        + "failed_count = failed_count + ? WHERE id = ?", rows,
                rows.size(), (ps, e) -> {
                    ps.setInt(1, e.getValue()[0]);
                    ps.setInt(2, e.getValue()[1]);
                    ps.setInt(3, e.getValue()[2]);
                    ps.setInt(4, e.getValue()[3]);
                    ps.setObject(5, e.getKey());
                });
    }

    @Override
    public List<Recipient> recipients(String schema, UUID campaignId, String afterPhone, int limit) {
        String sql = "SELECT r.phone, r.client_id, r.client_name, r.store_name, r.status, r.wa_message_id, r.error, "
                + "m.status AS delivery FROM " + table(schema, "whatsapp_campaign_recipients") + " r LEFT JOIN "
                + table(schema, "whatsapp_messages") + " m ON m.wa_message_id = r.wa_message_id WHERE "
                + "r.campaign_id = ?" + (afterPhone == null ? "" : " AND r.phone > ?") + " ORDER BY r.phone LIMIT ?";
        RowMapper<Recipient> mapper = (rs, n) -> new Recipient(rs.getString(1), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), RecipientStatus.valueOf(rs.getString(5)), rs.getString(6),
                rs.getString(7), rs.getString(8));
        return afterPhone == null ? jdbc.query(sql, mapper, campaignId, limit)
                : jdbc.query(sql, mapper, campaignId, afterPhone, limit);
    }

    // ── mapping ───────────────────────────────────────────────────────────────────────────────────────────────────

    private static final RowMapper<CampaignRow> ROW = (rs, n) -> {
        Campaign.FrozenTemplate frozen = rs.getString("template_name") == null ? null
                : new Campaign.FrozenTemplate(rs.getString("template_name"), rs.getString("template_language"),
                rs.getString("template_body"), rs.getString("template_header"));
        Campaign c = new Campaign(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                CampaignStatus.valueOf(rs.getString("status")), uuids(rs.getArray("store_ids")),
                rs.getObject("template_id", UUID.class), variables(rs.getString("variables")),
                rs.getString("header_media_id"), rs.getString("header_media_link"), instant(rs, "scheduled_at"),
                instant(rs, "started_at"), instant(rs, "sent_at"),
                new Campaign.Counters(rs.getInt("targeted_count"), rs.getInt("sent_count"),
                        rs.getInt("delivered_count"), rs.getInt("read_count"), rs.getInt("failed_count"),
                        rs.getInt("skipped_count"), rs.getInt("uncertain_count")),
                frozen, rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"),
                new PurchaseCriteria(rs.getObject("inactive_for_days", Integer.class),
                        rs.getObject("purchased_within_days", Integer.class),
                        rs.getObject("total_spent_min", Long.class), rs.getObject("total_spent_max", Long.class),
                        rs.getObject("total_invoices_min", Integer.class),
                        rs.getObject("total_invoices_max", Integer.class)));
        return frozen != null
                ? new CampaignRow(c, frozen.name(), frozen.language(), frozen.bodyText(), frozen.headerFormat())
                : new CampaignRow(c, rs.getString("t_name"), rs.getString("t_language"), rs.getString("t_body"),
                rs.getString("t_header"));
    };

    static String json(List<VariableMapping> variables) {
        List<Map<String, String>> list = new ArrayList<>();
        for (VariableMapping v : variables) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("source", v.source().name());
            if (v.value() != null) {
                m.put("value", v.value());
            }
            list.add(m);
        }
        try {
            return JSON.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("variables not serializable", e);
        }
    }

    static List<VariableMapping> variables(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<VariableMapping> out = new ArrayList<>();
            for (Map<String, String> m : JSON.readValue(json, VARIABLES)) {
                out.add(new VariableMapping(VariableSource.parse(m.get("source")).orElse(VariableSource.STATIC),
                        m.get("value")));
            }
            return out;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(v -> v instanceof UUID u ? u : UUID.fromString(v.toString())).toList();
    }

    private static int count(List<Outcome> outcomes, RecipientStatus status) {
        return (int) outcomes.stream().filter(o -> o.status() == status).count();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
