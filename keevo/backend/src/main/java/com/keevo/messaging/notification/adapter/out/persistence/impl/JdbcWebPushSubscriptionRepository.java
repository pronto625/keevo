package com.keevo.messaging.notification.adapter.out.persistence.impl;

import com.keevo.messaging.notification.domain.model.WebPushSubscription;
import com.keevo.messaging.notification.domain.port.out.WebPushSubscriptionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Story 19.9b — {@code public.web_push_subscriptions} over raw JDBC (fully-qualified table). The subscribe rule is one
 * atomic {@code INSERT … ON CONFLICT (endpoint) DO UPDATE … WHERE same user OR same keys}: no read-then-write race.
 */
@Component
public class JdbcWebPushSubscriptionRepository implements WebPushSubscriptionRepository {

    static final String UPSERT_SQL = "INSERT INTO public.web_push_subscriptions "
            + "(id, user_id, endpoint, p256dh, auth, user_agent, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, now(), now()) "
            + "ON CONFLICT (endpoint) DO UPDATE SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, "
            + "auth = EXCLUDED.auth, user_agent = EXCLUDED.user_agent, updated_at = now() "
            + "WHERE web_push_subscriptions.user_id = EXCLUDED.user_id "
            + "OR (web_push_subscriptions.p256dh = EXCLUDED.p256dh AND web_push_subscriptions.auth = EXCLUDED.auth)";
    static final String DELETE_OWN_SQL =
            "DELETE FROM public.web_push_subscriptions WHERE user_id = ? AND endpoint = ?";
    static final String FIND_BY_USERS_SQL = "SELECT id, user_id, endpoint, p256dh, auth, user_agent "
            + "FROM public.web_push_subscriptions WHERE user_id = ANY(?)";
    static final String DELETE_BY_IDS_SQL = "DELETE FROM public.web_push_subscriptions WHERE id = ANY(?)";

    private final JdbcTemplate jdbc;

    public JdbcWebPushSubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean upsert(WebPushSubscription s) {
        return jdbc.update(UPSERT_SQL, s.id(), s.userId(), s.endpoint(), s.p256dh(), s.auth(), s.userAgent()) > 0;
    }

    @Override
    public int deleteByUserAndEndpoint(UUID userId, String endpoint) {
        return jdbc.update(DELETE_OWN_SQL, userId, endpoint);
    }

    @Override
    public List<WebPushSubscription> findByUserIds(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(FIND_BY_USERS_SQL);
            ps.setArray(1, con.createArrayOf("uuid", userIds.toArray()));
            return ps;
        }, (rs, n) -> new WebPushSubscription((UUID) rs.getObject("id"), (UUID) rs.getObject("user_id"),
                rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth"), rs.getString("user_agent")));
    }

    @Override
    public int deleteByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(DELETE_BY_IDS_SQL);
            ps.setArray(1, con.createArrayOf("uuid", ids.toArray()));
            return ps;
        });
    }
}
