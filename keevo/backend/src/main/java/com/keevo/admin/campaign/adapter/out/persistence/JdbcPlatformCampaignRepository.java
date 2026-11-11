package com.keevo.admin.campaign.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.admin.campaign.domain.model.AudienceMember;
import com.keevo.admin.campaign.domain.model.Channel;
import com.keevo.admin.campaign.domain.model.ChannelStats;
import com.keevo.admin.campaign.domain.model.HistoryEntry;
import com.keevo.admin.campaign.domain.model.PlatformCampaign;
import com.keevo.admin.campaign.domain.model.PlatformCampaignStatus;
import com.keevo.admin.campaign.domain.model.Segment;
import com.keevo.admin.campaign.domain.model.WhatsAppContent;
import com.keevo.admin.campaign.domain.port.out.PlatformCampaignRepository;
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
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.6 — {@link PlatformCampaignRepository} on the public tables, plain JDBC. Costs: list = index scan
 * {@code idx_platform_campaigns_created} with LIMIT + 1 stats query ({@code = ANY}, PK); claim of due campaigns =
 * partial index {@code idx_platform_campaigns_due} with LIMIT and SKIP LOCKED; claim of recipients = partial index
 * {@code idx_platform_campaign_recipients_pending} in index order with LIMIT and SKIP LOCKED; outcomes = one JDBC
 * batch + one batch of per-channel counter UPDATEs; audience insert = JDBC batches of 500.
 */
@Component
public class JdbcPlatformCampaignRepository implements PlatformCampaignRepository {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String COLUMNS = "id, name, description, status, channels, title, message, segment::text AS "
            + "segment, whatsapp::text AS whatsapp, scheduled_at, started_at, sent_at, targeted_count, created_by, "
            + "created_at, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcPlatformCampaignRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(PlatformCampaign c) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO public.platform_campaigns (id, name, description, status, "
                    + "channels, title, message, segment, whatsapp, scheduled_at, next_run_at, targeted_count, "
                    + "created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, "
                    + "?, ?)");
            ps.setObject(1, c.id());
            ps.setString(2, c.name());
            ps.setString(3, c.description());
            ps.setString(4, c.status().name());
            ps.setArray(5, con.createArrayOf("varchar", c.channels().stream().map(Enum::name).toArray()));
            ps.setString(6, c.title());
            ps.setString(7, c.message());
            ps.setString(8, json(c.segment()));
            ps.setString(9, c.whatsapp() == null ? null : json(c.whatsapp()));
            ps.setTimestamp(10, ts(c.scheduledAt()));
            ps.setTimestamp(11, c.status() == PlatformCampaignStatus.SCHEDULED ? ts(c.scheduledAt()) : null);
            ps.setInt(12, c.targetedCount());
            ps.setObject(13, c.createdBy());
            ps.setTimestamp(14, ts(c.createdAt()));
            ps.setTimestamp(15, ts(c.updatedAt()));
            return ps;
        });
    }

    @Override
    public boolean updateEditable(PlatformCampaign c) {
        return jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE public.platform_campaigns SET name = ?, description = ?, status = ?, "
                    + "channels = ?, title = ?, message = ?, segment = ?::jsonb, whatsapp = ?::jsonb, scheduled_at = ?, "
                    + "next_run_at = ?, targeted_count = ?, updated_at = ? WHERE id = ? AND status IN ('DRAFT', "
                    + "'SCHEDULED')");
            ps.setString(1, c.name());
            ps.setString(2, c.description());
            ps.setString(3, c.status().name());
            ps.setArray(4, con.createArrayOf("varchar", c.channels().stream().map(Enum::name).toArray()));
            ps.setString(5, c.title());
            ps.setString(6, c.message());
            ps.setString(7, json(c.segment()));
            ps.setString(8, c.whatsapp() == null ? null : json(c.whatsapp()));
            ps.setTimestamp(9, ts(c.scheduledAt()));
            ps.setTimestamp(10, c.status() == PlatformCampaignStatus.SCHEDULED ? ts(c.scheduledAt()) : null);
            ps.setInt(11, c.targetedCount());
            ps.setTimestamp(12, ts(c.updatedAt()));
            ps.setObject(13, c.id());
            return ps;
        }) == 1;
    }

    @Override
    public boolean deleteUnsent(UUID id) {
        return jdbc.update("DELETE FROM public.platform_campaigns WHERE id = ? AND status IN ('DRAFT', 'SCHEDULED', "
                + "'CANCELLED')", id) == 1;
    }

    @Override
    public Optional<PlatformCampaign> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM public.platform_campaigns WHERE id = ?", ROW, id).stream()
                .findFirst();
    }

    @Override
    public List<PlatformCampaign> page(Instant beforeCreatedAt, UUID beforeId, int limit) {
        if (beforeCreatedAt == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM public.platform_campaigns ORDER BY created_at DESC, id DESC "
                    + "LIMIT ?", ROW, limit);
        }
        if (beforeId == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM public.platform_campaigns WHERE created_at < ? "
                    + "ORDER BY created_at DESC, id DESC LIMIT ?", ROW, ts(beforeCreatedAt), limit);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM public.platform_campaigns WHERE (created_at, id) < (?, ?) "
                + "ORDER BY created_at DESC, id DESC LIMIT ?", ROW, ts(beforeCreatedAt), beforeId, limit);
    }

    @Override
    public boolean unschedule(UUID id, Instant now) {
        return jdbc.update("UPDATE public.platform_campaigns SET status = 'DRAFT', scheduled_at = NULL, next_run_at = NULL, "
                + "updated_at = ? WHERE id = ? AND status = 'SCHEDULED'", ts(now), id) == 1;
    }

    @Override
    public boolean cancel(UUID id, Instant now) {
        return jdbc.update("UPDATE public.platform_campaigns SET status = 'CANCELLED', next_run_at = NULL, "
                + "updated_at = ? WHERE id = ? AND status = 'SCHEDULED'", ts(now), id) == 1;
    }

    @Override
    public boolean start(UUID id, int targeted, Instant now) {
        return jdbc.update("UPDATE public.platform_campaigns SET status = 'SENDING', targeted_count = ?, started_at = ?, "
                + "next_run_at = ?, updated_at = ? WHERE id = ? AND status IN ('DRAFT', 'SCHEDULED')", targeted,
                ts(now), ts(now), ts(now), id) == 1;
    }

    @Override
    public void insertAudience(UUID id, Map<Channel, List<AudienceMember>> byChannel) {
        List<Object[]> rows = new ArrayList<>();
        byChannel.forEach((channel, members) -> {
            for (AudienceMember m : members) {
                String address = switch (channel) {
                    case PUSH -> null;
                    case EMAIL -> m.email();
                    case WHATSAPP, SMS -> m.phone();
                };
                rows.add(new Object[]{id, channel.name(), m.userId(), m.tenantId(), address, bounded(m.firstName(), 100),
                        bounded(m.lastName(), 100), bounded(m.companyName(), 200)});
            }
        });
        for (int from = 0; from < rows.size(); from += 500) {
            jdbc.batchUpdate("INSERT INTO public.platform_campaign_recipients (campaign_id, channel, user_id, "
                    + "tenant_id, address, first_name, last_name, company_name, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, "
                    + "'PENDING') ON CONFLICT DO NOTHING", rows.subList(from, Math.min(rows.size(), from + 500)));
        }
        List<Object[]> stats = new ArrayList<>();
        byChannel.forEach((channel, members) -> stats.add(new Object[]{id, channel.name(), members.size()}));
        jdbc.batchUpdate("INSERT INTO public.platform_campaign_stats (campaign_id, channel, targeted) VALUES (?, ?, ?) "
                + "ON CONFLICT (campaign_id, channel) DO UPDATE SET targeted = EXCLUDED.targeted", stats);
    }

    @Override
    public List<UUID> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.query("UPDATE public.platform_campaigns SET next_run_at = ? WHERE id IN (SELECT id FROM "
                        + "public.platform_campaigns WHERE status IN ('SCHEDULED', 'SENDING') AND next_run_at <= ? "
                        + "ORDER BY next_run_at LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING id",
                (rs, n) -> rs.getObject(1, UUID.class), ts(leaseUntil), ts(now), limit);
    }

    @Override
    public void reschedule(UUID id, Instant at) {
        jdbc.update("UPDATE public.platform_campaigns SET next_run_at = ? WHERE id = ? AND status IN ('SCHEDULED', "
                + "'SENDING')", ts(at), id);
    }

    @Override
    public List<Claimed> claimRecipients(UUID id, int limit, Instant now) {
        return jdbc.query("UPDATE public.platform_campaign_recipients r SET status = 'SENDING', updated_at = ? WHERE "
                        + "(r.campaign_id, r.channel, r.user_id) IN (SELECT campaign_id, channel, user_id FROM "
                        + "public.platform_campaign_recipients WHERE campaign_id = ? AND status = 'PENDING' ORDER BY "
                        + "channel, user_id LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING r.channel, r.user_id, r.tenant_id, "
                        + "(SELECT t.schema_name FROM public.tenants t WHERE t.id = r.tenant_id AND t.status <> "
                        + "'DELETED') AS schema_name, r.address, r.first_name, r.last_name, r.company_name",
                (rs, n) -> new Claimed(Channel.valueOf(rs.getString(1)), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8)),
                ts(now), id, limit);
    }

    @Override
    public int expireStuck(UUID id, Instant before, Instant now) {
        Integer n = jdbc.queryForObject("WITH s AS (UPDATE public.platform_campaign_recipients SET status = 'UNCERTAIN', "
                        + "error = ?, updated_at = ? WHERE campaign_id = ? AND status = 'SENDING' AND updated_at < ? "
                        + "RETURNING channel), g AS (SELECT channel, count(*)::int AS n FROM s GROUP BY channel), "
                        + "u AS (UPDATE public.platform_campaign_stats st SET uncertain = st.uncertain + g.n FROM g "
                        + "WHERE st.campaign_id = ? AND st.channel = g.channel RETURNING 1) "
                        + "SELECT COALESCE(SUM(n), 0)::int FROM g", Integer.class,
                "Envoi interrompu : message peut-être reçu", ts(now), id, ts(before), id);
        return n == null ? 0 : n;
    }

    @Override
    public void recordOutcomes(UUID id, List<Outcome> outcomes, Instant now) {
        if (outcomes.isEmpty()) {
            return;
        }
        int[][] updated = jdbc.batchUpdate("UPDATE public.platform_campaign_recipients SET status = ?, wa_message_id = ?, error = ?, "
                        + "updated_at = ? WHERE campaign_id = ? AND channel = ? AND user_id = ? AND status = 'SENDING'",
                outcomes, outcomes.size(), (ps, o) -> {
                    ps.setString(1, o.status());
                    ps.setString(2, o.waMessageId());
                    ps.setString(3, o.error() == null ? null : bounded(o.error(), 200));
                    ps.setTimestamp(4, ts(now));
                    ps.setObject(5, id);
                    ps.setString(6, o.channel().name());
                    ps.setObject(7, o.userId());
                });
        // only the rows really moved count (a row already expired UNCERTAIN is not counted twice)
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
        Map<Channel, int[]> counts = new EnumMap<>(Channel.class);
        for (Outcome o : applied) {
            int[] c = counts.computeIfAbsent(o.channel(), k -> new int[4]);
            switch (o.status()) {
                case "SENT" -> c[0]++;
                case "FAILED" -> c[1]++;
                case "SKIPPED" -> c[2]++;
                case "UNCERTAIN" -> c[3]++;
                default -> {
                }
            }
        }
        List<Object[]> updates = new ArrayList<>();
        counts.forEach((channel, c) -> {
            if (c[0] + c[1] + c[2] + c[3] > 0) {
                updates.add(new Object[]{c[0], c[1], c[2], c[3], id, channel.name()});
            }
        });
        if (!updates.isEmpty()) {
            jdbc.batchUpdate("UPDATE public.platform_campaign_stats SET sent = sent + ?, failed = failed + ?, "
                    + "skipped = skipped + ?, uncertain = uncertain + ? WHERE campaign_id = ? AND channel = ?", updates);
        }
    }

    @Override
    public boolean hasOpenRecipients(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM public.platform_campaign_recipients "
                + "WHERE campaign_id = ? AND status = 'PENDING') OR EXISTS (SELECT 1 FROM "
                + "public.platform_campaign_recipients WHERE campaign_id = ? AND status = 'SENDING')", Boolean.class,
                id, id));
    }

    @Override
    public boolean complete(UUID id, Instant now) {
        return jdbc.update("UPDATE public.platform_campaigns SET status = 'SENT', sent_at = ?, next_run_at = NULL, "
                + "updated_at = ? WHERE id = ? AND status = 'SENDING'", ts(now), ts(now), id) == 1;
    }

    @Override
    public Map<UUID, List<ChannelStats>> stats(Collection<UUID> ids) {
        Map<UUID, List<ChannelStats>> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT campaign_id, channel, targeted, sent, delivered, read, failed, skipped, uncertain FROM "
                        + "public.platform_campaign_stats WHERE campaign_id = ANY (CAST(string_to_array(?, ',') AS "
                        + "uuid[])) ORDER BY campaign_id, channel",
                rs -> {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(new ChannelStats(
                            Channel.valueOf(rs.getString(2)), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                            rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9)));
                }, String.join(",", ids.stream().map(UUID::toString).toList()));
        return out;
    }

    @Override
    public void applyWhatsAppChanges(Map<UUID, int[]> deltas) {
        List<Object[]> rows = new ArrayList<>();
        deltas.forEach((id, d) -> rows.add(new Object[]{d[0], d[1], d[2], d[3], id}));
        if (!rows.isEmpty()) {
            // plain sums (no clamp): order-independent with the batch's own bookkeeping
            jdbc.batchUpdate("UPDATE public.platform_campaign_stats SET sent = sent + ?, delivered = delivered + ?, "
                    + "read = read + ?, failed = failed + ? WHERE campaign_id = ? AND channel = 'WHATSAPP'", rows);
        }
    }

    @Override
    public void addEvent(UUID campaignId, String campaignName, String action, UUID actorId, String detail,
                         Instant at) {
        jdbc.update("INSERT INTO public.platform_campaign_events (id, campaign_id, campaign_name, action, actor_id, "
                        + "detail, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), campaignId,
                bounded(campaignName, 100), action, actorId, detail == null ? null : bounded(detail, 500), ts(at));
    }

    @Override
    public List<HistoryEntry> history(UUID campaignId) {
        return jdbc.query("SELECT e.action, e.detail, e.occurred_at, e.actor_id, NULLIF(btrim(concat_ws(' ', "
                        + "u.first_name, u.last_name)), '') AS actor FROM public.platform_campaign_events e LEFT JOIN "
                        + "public.users u ON u.id = e.actor_id WHERE e.campaign_id = ? ORDER BY e.occurred_at, e.id",
                (rs, n) -> new HistoryEntry(rs.getString(1), actionLabel(rs.getString(1)),
                        rs.getObject(4) == null ? "Système" : rs.getString(5) == null ? "Super-administrateur"
                                : rs.getString(5), rs.getString(2), rs.getTimestamp(3).toInstant()),
                campaignId);
    }

    static String actionLabel(String action) {
        return switch (action) {
            case "CREATED" -> "Créée";
            case "UPDATED" -> "Modifiée";
            case "DELETED" -> "Supprimée";
            case "SEND_STARTED" -> "Envoi lancé";
            case "SCHEDULED" -> "Programmée";
            case "CANCELLED" -> "Annulée";
            case "COMPLETED" -> "Envoi terminé";
            default -> "Modifiée";
        };
    }

    // ── mapping ───────────────────────────────────────────────────────────────────────────────────────────────────

    private static final RowMapper<PlatformCampaign> ROW = (rs, n) -> new PlatformCampaign(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
            PlatformCampaignStatus.valueOf(rs.getString("status")), channels(rs.getArray("channels")),
            rs.getString("title"), rs.getString("message"), read(rs.getString("segment"), Segment.class),
            read(rs.getString("whatsapp"), WhatsAppContent.class), instant(rs, "scheduled_at"),
            instant(rs, "started_at"), instant(rs, "sent_at"), rs.getInt("targeted_count"),
            rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"));

    private static List<Channel> channels(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(v -> Channel.valueOf(v.toString())).toList();
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("not serializable", e);
        }
    }

    private static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String bounded(String v, int max) {
        return v == null ? null : v.length() > max ? v.substring(0, max) : v;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
