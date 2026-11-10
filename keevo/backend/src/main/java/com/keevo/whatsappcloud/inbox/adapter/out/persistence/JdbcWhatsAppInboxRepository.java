package com.keevo.whatsappcloud.inbox.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.whatsappcloud.inbox.domain.model.ChatMessage;
import com.keevo.whatsappcloud.inbox.domain.model.Conversation;
import com.keevo.whatsappcloud.inbox.domain.model.ConversationDelta;
import com.keevo.whatsappcloud.inbox.domain.model.StatusUpdate;
import com.keevo.whatsappcloud.inbox.domain.port.out.WhatsAppInboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.5 — {@link WhatsAppInboxRepository} on the module tables of the tenant schema, plain JDBC; every statement
 * names {@code "kv_xxxxxx".<table>} after {@link TenantSchema#validate}. Costs: the inserts and the conversation upserts
 * are JDBC batches (one round trip each); the statuses one {@code UPDATE … FROM unnest(…)} on
 * {@code uq_whatsapp_messages_wa_id}; the list an index scan of {@code idx_whatsapp_conversations_last_at}; a thread
 * {@code idx_whatsapp_messages_thread} with {@code LIMIT}; mark read the partial {@code idx_whatsapp_messages_unread};
 * the unread badge the partial {@code idx_whatsapp_conversations_unread}.
 */
@Component
public class JdbcWhatsAppInboxRepository implements WhatsAppInboxRepository {

    private static final String RANK = "CASE %s WHEN 'READ' THEN 3 WHEN 'DELIVERED' THEN 2 WHEN 'SENT' THEN 1 "
            + "WHEN 'FAILED' THEN 1 ELSE 0 END";

    private static final RowMapper<ChatMessage> MESSAGE = (rs, n) -> new ChatMessage(rs.getObject("id", UUID.class),
            rs.getString("contact_phone"), ChatMessage.Direction.valueOf(rs.getString("direction")),
            rs.getString("type"), rs.getString("body"), rs.getString("wa_message_id"), rs.getString("status"),
            instant(rs.getTimestamp("read_at")), rs.getObject("sent_by", UUID.class),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<Conversation> CONVERSATION = (rs, n) -> new Conversation(
            rs.getString("contact_phone"), rs.getString("contact_name"), rs.getString("last_message"),
            rs.getString("last_type"), ChatMessage.Direction.valueOf(rs.getString("last_direction")),
            rs.getTimestamp("last_at").toInstant(), rs.getInt("unread_count"),
            instant(rs.getTimestamp("last_inbound_at")));

    private final JdbcTemplate jdbc;

    public JdbcWhatsAppInboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String table(String schema, String table) {
        TenantSchema.validate(schema);
        return "\"" + schema + "\"." + table;
    }

    @Override
    public boolean[] insertAll(String schema, List<ChatMessage> messages) {
        boolean[] inserted = new boolean[messages.size()];
        if (messages.isEmpty()) {
            return inserted;
        }
        int[][] counts = jdbc.batchUpdate("INSERT INTO " + table(schema, "whatsapp_messages") + " (id, contact_phone, "
                        + "direction, type, body, wa_message_id, status, read_at, sent_by, created_at) VALUES "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (wa_message_id) DO NOTHING",
                messages, messages.size(), (ps, m) -> {
                    ps.setObject(1, m.id());
                    ps.setString(2, m.contactPhone());
                    ps.setString(3, m.direction().name());
                    ps.setString(4, m.type());
                    ps.setString(5, m.body());
                    ps.setString(6, m.waMessageId());
                    ps.setString(7, m.status());
                    ps.setTimestamp(8, m.readAt() == null ? null : Timestamp.from(m.readAt()));
                    ps.setObject(9, m.sentBy());
                    ps.setTimestamp(10, Timestamp.from(m.createdAt()));
                });
        int i = 0;
        for (int[] batch : counts) {
            for (int c : batch) {
                // SUCCESS_NO_INFO (-2) is never returned by PostgreSQL without rewritten batches; count it inserted
                inserted[i++] = c != 0;
            }
        }
        return inserted;
    }

    @Override
    public void upsertConversations(String schema, List<ConversationDelta> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO " + table(schema, "whatsapp_conversations") + " AS c (contact_phone, "
                        + "contact_name, last_message, last_type, last_direction, last_at, unread_count, "
                        + "last_inbound_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (contact_phone) DO UPDATE SET "
                        + "contact_name = COALESCE(EXCLUDED.contact_name, c.contact_name), "
                        + "last_message = CASE WHEN EXCLUDED.last_at >= c.last_at THEN EXCLUDED.last_message "
                        + "ELSE c.last_message END, "
                        + "last_type = CASE WHEN EXCLUDED.last_at >= c.last_at THEN EXCLUDED.last_type "
                        + "ELSE c.last_type END, "
                        + "last_direction = CASE WHEN EXCLUDED.last_at >= c.last_at THEN EXCLUDED.last_direction "
                        + "ELSE c.last_direction END, "
                        + "last_at = GREATEST(c.last_at, EXCLUDED.last_at), "
                        + "unread_count = c.unread_count + EXCLUDED.unread_count, "
                        + "last_inbound_at = GREATEST(c.last_inbound_at, EXCLUDED.last_inbound_at)",
                deltas, deltas.size(), (ps, d) -> {
                    ps.setString(1, d.contactPhone());
                    ps.setString(2, d.contactName());
                    ps.setString(3, d.lastMessage());
                    ps.setString(4, d.lastType());
                    ps.setString(5, d.lastDirection().name());
                    ps.setTimestamp(6, Timestamp.from(d.lastAt()));
                    ps.setInt(7, d.unreadIncrement());
                    if (d.lastInboundAt() == null) {
                        ps.setNull(8, Types.TIMESTAMP);
                    } else {
                        ps.setTimestamp(8, Timestamp.from(d.lastInboundAt()));
                    }
                });
    }

    @Override
    public int applyStatuses(String schema, List<StatusUpdate> updates) {
        if (updates.isEmpty()) {
            return 0;
        }
        String[] ids = updates.stream().map(StatusUpdate::waMessageId).toArray(String[]::new);
        String[] statuses = updates.stream().map(StatusUpdate::status).toArray(String[]::new);
        return jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE " + table(schema, "whatsapp_messages") + " m SET status = v.status "
                    + "FROM unnest(?::varchar[], ?::varchar[]) AS v(wa_id, status) WHERE m.wa_message_id = v.wa_id "
                    + "AND m.direction = 'OUT' AND (" + RANK.formatted("v.status") + " > " + RANK.formatted("m.status")
                    + " OR (v.status = 'FAILED' AND m.status = 'SENT'))");
            ps.setArray(1, con.createArrayOf("varchar", ids));
            ps.setArray(2, con.createArrayOf("varchar", statuses));
            return ps;
        });
    }

    @Override
    public List<Conversation> conversations(String schema, int limit) {
        return jdbc.query("SELECT contact_phone, contact_name, last_message, last_type, last_direction, last_at, "
                + "unread_count, last_inbound_at FROM " + table(schema, "whatsapp_conversations")
                + " ORDER BY last_at DESC, contact_phone LIMIT ?", CONVERSATION, limit);
    }

    @Override
    public Optional<Conversation> conversation(String schema, String contactPhone) {
        return jdbc.query("SELECT contact_phone, contact_name, last_message, last_type, last_direction, last_at, "
                        + "unread_count, last_inbound_at FROM " + table(schema, "whatsapp_conversations")
                        + " WHERE contact_phone = ?", CONVERSATION, contactPhone).stream().findFirst();
    }

    @Override
    public List<ChatMessage> thread(String schema, String contactPhone, Instant before, UUID beforeId, int limit) {
        String columns = "SELECT id, contact_phone, direction, type, body, wa_message_id, status, read_at, sent_by, "
                + "created_at FROM " + table(schema, "whatsapp_messages") + " WHERE contact_phone = ?";
        if (before == null) {
            return jdbc.query(columns + " ORDER BY created_at DESC, id DESC LIMIT ?", MESSAGE, contactPhone, limit);
        }
        if (beforeId == null) {
            return jdbc.query(columns + " AND created_at < ? ORDER BY created_at DESC, id DESC LIMIT ?", MESSAGE,
                    contactPhone, Timestamp.from(before), limit);
        }
        // keyset on the index order (Meta's timestamps are whole seconds: several messages share one)
        return jdbc.query(columns + " AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?",
                MESSAGE, contactPhone, Timestamp.from(before), beforeId, limit);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void markRead(String schema, String contactPhone, Instant now) {
        int read = jdbc.update("UPDATE " + table(schema, "whatsapp_messages") + " SET read_at = ? WHERE contact_phone = ? "
                + "AND direction = 'IN' AND read_at IS NULL", Timestamp.from(now), contactPhone);
        if (read > 0) {
            // recomputed on the partial unread index: a webhook inserting meanwhile never leaves a stale count
            jdbc.update("UPDATE " + table(schema, "whatsapp_conversations") + " SET unread_count = (SELECT count(*) "
                    + "FROM " + table(schema, "whatsapp_messages") + " m WHERE m.contact_phone = ? AND m.direction = "
                    + "'IN' AND m.read_at IS NULL) WHERE contact_phone = ?", contactPhone, contactPhone);
        }
    }

    @Override
    public long unreadCount(String schema) {
        Long n = jdbc.queryForObject("SELECT COALESCE(SUM(unread_count), 0) FROM "
                + table(schema, "whatsapp_conversations") + " WHERE unread_count > 0", Long.class);
        return n == null ? 0 : n;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
