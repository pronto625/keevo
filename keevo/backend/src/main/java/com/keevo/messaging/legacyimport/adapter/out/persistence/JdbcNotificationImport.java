package com.keevo.messaging.legacyimport.adapter.out.persistence;

import com.keevo.messaging.api.NotificationImportPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Story 24.6 — {@link NotificationImportPort} as one {@code INSERT … SELECT FROM unnest(…) ON CONFLICT (id) DO NOTHING
 * RETURNING id} on the bound tenant's {@code notifications} (messaging V2: type ≤ 64, title ≤ 150, body ≤ 1000,
 * {@code data} JSON object). Nothing is pushed: this writes the inbox only (the live path is {@code PushNotifier}).
 */
@Component
class JdbcNotificationImport implements NotificationImportPort {

    private final JdbcTemplate jdbc;

    JdbcNotificationImport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<UUID> importNotifications(String tenantSchema, List<ImportedNotification> notifications) {
        TenantContext.requireCurrent(tenantSchema);
        String schema = TenantSchema.validate(tenantSchema);
        if (notifications.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (ImportedNotification n : notifications) {
            require(ids.add(n.id()), "duplicate notification");
            require(n.userId() != null && n.createdAt() != null, "notification owner");
            require(n.type() != null && !n.type().isBlank() && max(n.type(), MAX_TYPE), "notification type");
            require(n.title() != null && max(n.title(), MAX_TITLE) && n.body() != null && max(n.body(), MAX_BODY),
                    "notification text");
            require(n.data() == null || n.data().strip().startsWith("{"), "notification data");
        }
        String sql = "INSERT INTO \"" + schema + "\".notifications (id, user_id, type, title, body, data, read_at, "
                + "created_at) SELECT x.id, x.user_id, x.type, x.title, x.body, x.data::jsonb, "
                + "x.read_at::timestamptz, x.created_at::timestamptz "
                + "FROM unnest(?::uuid[], ?::uuid[], ?::varchar[], ?::varchar[], ?::varchar[], ?::text[], ?::text[], "
                + "?::text[]) AS x(id, user_id, type, title, body, data, read_at, created_at) "
                + "ON CONFLICT (id) DO NOTHING RETURNING id";
        Set<UUID> inserted = new LinkedHashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("uuid", column(notifications, ImportedNotification::id)));
            ps.setArray(2, con.createArrayOf("uuid", column(notifications, ImportedNotification::userId)));
            ps.setArray(3, con.createArrayOf("varchar", column(notifications, ImportedNotification::type)));
            ps.setArray(4, con.createArrayOf("varchar", column(notifications, ImportedNotification::title)));
            ps.setArray(5, con.createArrayOf("varchar", column(notifications, ImportedNotification::body)));
            ps.setArray(6, con.createArrayOf("text", column(notifications, ImportedNotification::data)));
            ps.setArray(7, con.createArrayOf("text", column(notifications, n -> text(n.readAt()))));
            ps.setArray(8, con.createArrayOf("text", column(notifications, n -> text(n.createdAt()))));
            return ps;
        }, rs -> {
            inserted.add(rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    @Override
    public Set<UUID> importWebPushSubscriptions(List<ImportedWebPush> subscriptions) {
        if (subscriptions.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        Set<String> endpoints = new HashSet<>();
        for (ImportedWebPush w : subscriptions) {
            require(ids.add(w.id()) && w.userId() != null && w.createdAt() != null && w.updatedAt() != null,
                    "web push owner");
            require(w.endpoint() != null && max(w.endpoint(), 2048) && endpoints.add(w.endpoint()),
                    "web push endpoint");
            require(w.p256dh() != null && max(w.p256dh(), 200) && w.auth() != null && max(w.auth(), 100)
                    && (w.userAgent() == null || max(w.userAgent(), 500)), "web push keys");
        }
        String sql = "INSERT INTO public.web_push_subscriptions (id, user_id, endpoint, p256dh, auth, user_agent, "
                + "created_at, updated_at) SELECT x.id, x.user_id, x.endpoint, x.p256dh, x.auth, x.ua, "
                + "x.created_at::timestamptz, x.updated_at::timestamptz FROM unnest(?::uuid[], ?::uuid[], "
                + "?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], ?::text[], ?::text[]) AS x(id, user_id, "
                + "endpoint, p256dh, auth, ua, created_at, updated_at) ON CONFLICT DO NOTHING RETURNING id";
        Set<UUID> inserted = new LinkedHashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("uuid", webPush(subscriptions, ImportedWebPush::id)));
            ps.setArray(2, con.createArrayOf("uuid", webPush(subscriptions, ImportedWebPush::userId)));
            ps.setArray(3, con.createArrayOf("varchar", webPush(subscriptions, ImportedWebPush::endpoint)));
            ps.setArray(4, con.createArrayOf("varchar", webPush(subscriptions, ImportedWebPush::p256dh)));
            ps.setArray(5, con.createArrayOf("varchar", webPush(subscriptions, ImportedWebPush::auth)));
            ps.setArray(6, con.createArrayOf("varchar", webPush(subscriptions, ImportedWebPush::userAgent)));
            ps.setArray(7, con.createArrayOf("text", webPush(subscriptions, w -> text(w.createdAt()))));
            ps.setArray(8, con.createArrayOf("text", webPush(subscriptions, w -> text(w.updatedAt()))));
            return ps;
        }, rs -> {
            inserted.add(rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    private static Object[] webPush(List<ImportedWebPush> rows, Function<ImportedWebPush, Object> field) {
        return rows.stream().map(field).toArray();
    }

    private static boolean max(String value, int length) {
        return value.codePointCount(0, value.length()) <= length;
    }

    private static String text(Instant at) {
        return at == null ? null : at.toString();
    }

    private static Object[] column(List<ImportedNotification> rows, Function<ImportedNotification, Object> field) {
        return rows.stream().map(field).toArray();
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("invalid imported notification: " + what);
        }
    }
}
