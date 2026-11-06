package com.keevo.messaging.notification.adapter.out.persistence.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.messaging.notification.domain.model.InboxNotification;
import com.keevo.messaging.notification.domain.port.out.NotificationInboxRepository;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 19.9 — JDBC {@link NotificationInboxRepository}: schema-qualified SQL ({@code "kv_xxxxxx".notifications},
 * schema validated by {@link TenantSchema}) so it works from async listeners where {@code search_path} is not set.
 * Inserts go in one JDBC batch (no per-row round trip).
 */
@Component
public class JdbcNotificationInboxAdapter implements NotificationInboxRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcNotificationInboxAdapter.class);

    static final int TYPE_MAX = 64;
    static final int TITLE_MAX = 150;
    static final int BODY_MAX = 1000;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcNotificationInboxAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insertAll(String schema, Collection<UUID> userIds, NotificationPayload payload) {
        if (userIds == null || userIds.isEmpty()) return;
        String table = table(schema);
        String type = clean(payload.type(), TYPE_MAX);
        String title = clean(payload.title(), TITLE_MAX);
        String body = clean(payload.body(), BODY_MAX);
        String data = data(payload);
        List<Object[]> rows = new ArrayList<>(userIds.size());
        for (UUID userId : userIds) {
            rows.add(new Object[]{userId, type, title, body, data});
        }
        jdbc.batchUpdate("INSERT INTO " + table + " (user_id, type, title, body, data) VALUES (?, ?, ?, ?, ?::jsonb)",
                rows);
    }

    @Override
    public List<InboxNotification> page(String schema, UUID userId, int offset, int limit) {
        return jdbc.query("SELECT id, user_id, type, title, body, data::text AS data, read_at, created_at FROM "
                        + table(schema) + " WHERE user_id = ? ORDER BY created_at DESC, id DESC OFFSET ? LIMIT ?",
                rowMapper(), userId, offset, limit);
    }

    @Override
    public long countByUser(String schema, UUID userId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table(schema) + " WHERE user_id = ?", Long.class,
                userId);
        return n == null ? 0 : n;
    }

    @Override
    public long countUnread(String schema, UUID userId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table(schema)
                + " WHERE user_id = ? AND read_at IS NULL", Long.class, userId);
        return n == null ? 0 : n;
    }

    @Override
    public int markRead(String schema, UUID id, UUID userId) {
        return jdbc.update("UPDATE " + table(schema) + " SET read_at = NOW() WHERE id = ? AND user_id = ? "
                + "AND read_at IS NULL", id, userId);
    }

    @Override
    public boolean exists(String schema, UUID id, UUID userId) {
        Boolean found = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + table(schema)
                + " WHERE id = ? AND user_id = ?)", Boolean.class, id, userId);
        return Boolean.TRUE.equals(found);
    }

    @Override
    public int markAllRead(String schema, UUID userId) {
        return jdbc.update("UPDATE " + table(schema) + " SET read_at = NOW() WHERE user_id = ? AND read_at IS NULL",
                userId);
    }

    @Override
    public Set<String> schemasWithInbox() {
        return new HashSet<>(jdbc.queryForList("SELECT schemaname FROM pg_catalog.pg_tables "
                + "WHERE tablename = 'notifications' AND schemaname ~ '^kv_'", String.class));
    }

    @Override
    public Purge purge(String schema, UUID tenantId, Instant readBefore) {
        String table = table(schema);
        int read = jdbc.update("DELETE FROM " + table + " WHERE read_at IS NOT NULL AND read_at < ?",
                readBefore.atOffset(ZoneOffset.UTC));
        // anti-join: scans this tenant's inbox (small: read rows are purged), memberships probed by their unique index
        int departed = jdbc.update("DELETE FROM " + table + " n WHERE NOT EXISTS (SELECT 1 FROM "
                + "public.user_tenant_memberships m WHERE m.user_id = n.user_id AND m.tenant_id = ? AND m.is_active)",
                tenantId);
        return new Purge(read, departed);
    }

    private RowMapper<InboxNotification> rowMapper() {
        return (rs, i) -> {
            Timestamp readAt = rs.getTimestamp("read_at");
            return new InboxNotification(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("type"),
                    rs.getString("title"),
                    rs.getString("body"),
                    parse(rs.getString("data")),
                    readAt == null ? null : readAt.toInstant(),
                    rs.getTimestamp("created_at").toInstant());
        };
    }

    /** metadata + {@code deepLink}; null when both are empty. */
    private String data(NotificationPayload payload) {
        Map<String, String> data = new LinkedHashMap<>();
        if (payload.metadata() != null) {
            payload.metadata().forEach((k, v) -> data.put(stripNul(k), stripNul(v)));
        }
        if (payload.deepLink() != null && !payload.deepLink().isBlank()) data.put("deepLink", stripNul(payload.deepLink()));
        if (data.isEmpty()) return null;
        try {
            return json.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Notification data not serializable", e);
        }
    }

    private Map<String, Object> parse(String data) {
        if (data == null || data.isBlank()) return Map.of();
        try {
            return json.readValue(data, MAP);
        } catch (JsonProcessingException e) {
            log.warn("[Inbox] unreadable notification data ({})", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** NUL removed (PostgreSQL refuses it in text and jsonb), then truncated to the column size; null → "". */
    static String clean(String value, int max) {
        return value == null ? "" : truncate(stripNul(value), max);
    }

    static String stripNul(String value) {
        return value == null || value.indexOf('\0') < 0 ? value : value.replace("\0", "");
    }

    /** Truncated on a code point boundary (never splits a surrogate pair). */
    static String truncate(String value, int max) {
        if (value.length() <= max) return value;
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    @SuppressWarnings("SqlSourceToSinkFlow")
    private static String table(String schema) {
        try {
            TenantSchema.validate(schema);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid tenant schema: " + e.getMessage());
        }
        return "\"" + schema + "\".notifications";
    }
}
