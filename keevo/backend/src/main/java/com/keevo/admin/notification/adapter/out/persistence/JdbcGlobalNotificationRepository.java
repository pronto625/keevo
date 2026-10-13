package com.keevo.admin.notification.adapter.out.persistence;

import com.keevo.admin.notification.domain.model.Announcement;
import com.keevo.admin.notification.domain.model.GlobalNotification;
import com.keevo.admin.notification.domain.model.GlobalNotificationFailureReason;
import com.keevo.admin.notification.domain.model.GlobalNotificationStatus;
import com.keevo.admin.notification.domain.model.GlobalNotificationTarget;
import com.keevo.admin.notification.domain.model.PushTenantRef;
import com.keevo.admin.notification.domain.port.out.GlobalNotificationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JdbcGlobalNotificationRepository — {@code public.global_notifications} (Story 9-5, FR82). */
@Repository
public class JdbcGlobalNotificationRepository implements GlobalNotificationRepository {

    private static final String COLUMNS = "g.id, g.title, g.body, g.target, g.target_tenant_id, g.scheduled_at, "
            + "g.status, g.recipient_count, g.delivered_count, g.failure_count, g.failure_reason, g.created_by, g.created_at, "
            + "g.sent_at";

    static final String CLAIM_SQL =
            "UPDATE public.global_notifications g SET status = 'SENDING', claimed_at = ? "
            + "WHERE g.status = 'SCHEDULED' AND g.scheduled_at <= ? "
            + "RETURNING " + COLUMNS + ", NULL AS tenant_name";

    static final String FAIL_STUCK_SQL =
            "UPDATE public.global_notifications g SET status = 'FAILED', failure_reason = 'INTERRUPTED', "
            + "recipient_count = COALESCE(g.recipient_count, 0), delivered_count = COALESCE(g.delivered_count, 0), "
            + "failure_count = COALESCE(g.failure_count, 0), sent_at = ? "
            + "WHERE g.status = 'SENDING' AND (g.claimed_at IS NULL OR g.claimed_at < ?) "
            + "RETURNING " + COLUMNS + ", NULL AS tenant_name";

    private final JdbcTemplate jdbc;

    public JdbcGlobalNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(GlobalNotification n) {
        jdbc.update("INSERT INTO public.global_notifications (id, title, body, target, target_tenant_id, scheduled_at, "
                        + "status, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                n.id(), n.title(), n.body(), n.target().name(), n.targetTenantId(), Timestamp.from(n.scheduledAt()),
                n.status().name(), n.createdBy(), Timestamp.from(n.createdAt()));
    }

    @Override
    public List<GlobalNotification> findCreatedSince(Instant since) {
        return jdbc.query("SELECT " + COLUMNS + ", t.name AS tenant_name FROM public.global_notifications g "
                        + "LEFT JOIN public.tenants t ON t.id = g.target_tenant_id "
                        + "WHERE g.created_at >= ? ORDER BY g.created_at DESC, g.id",
                MAPPER, Timestamp.from(since));
    }

    @Override
    public Optional<GlobalNotification> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + ", t.name AS tenant_name FROM public.global_notifications g "
                        + "LEFT JOIN public.tenants t ON t.id = g.target_tenant_id WHERE g.id = ?",
                MAPPER, id).stream().findFirst();
    }

    @Override
    public List<GlobalNotification> claimDue(Instant now) {
        return jdbc.query(CLAIM_SQL, MAPPER, Timestamp.from(now), Timestamp.from(now));
    }

    @Override
    public List<GlobalNotification> failStuck(Instant claimedBefore, Instant now) {
        return jdbc.query(FAIL_STUCK_SQL, MAPPER, Timestamp.from(now), Timestamp.from(claimedBefore));
    }

    @Override
    public boolean cancelIfScheduled(UUID id, Instant now) {
        return jdbc.update("UPDATE public.global_notifications SET status = 'CANCELLED', sent_at = ? "
                + "WHERE id = ? AND status = 'SCHEDULED'", Timestamp.from(now), id) == 1;
    }

    @Override
    public void complete(UUID id, boolean sent, GlobalNotificationFailureReason reason, int recipientCount,
                         int deliveredCount, int failureCount, Instant endedAt) {
        jdbc.update("UPDATE public.global_notifications SET status = ?, failure_reason = ?, recipient_count = ?, "
                        + "delivered_count = ?, failure_count = ?, sent_at = ? WHERE id = ? AND status = 'SENDING'",
                sent ? GlobalNotificationStatus.SENT.name() : GlobalNotificationStatus.FAILED.name(),
                sent || reason == null ? null : reason.name(),
                recipientCount, deliveredCount, failureCount, Timestamp.from(endedAt), id);
    }

    @Override
    public int purgeCreatedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM public.global_notifications WHERE created_at < ? AND status <> 'SCHEDULED'",
                Timestamp.from(cutoff));
    }

    @Override
    public List<Announcement> findSentFor(PushTenantRef tenant, Instant since) {
        return jdbc.query("SELECT id, title, body, sent_at FROM public.global_notifications "
                        + "WHERE status = 'SENT' AND sent_at >= ? AND (target = 'ALL' "
                        + "OR (target = 'PREMIUM' AND ? = 'PREMIUM') "
                        + "OR (target = 'TRIAL' AND ? = 'PREMIUM_TRIAL') "
                        + "OR (target = 'FREE' AND ? = 'FREE') "
                        + "OR (target = 'TENANT' AND target_tenant_id = ?)) "
                        + "ORDER BY sent_at, id",
                (rs, i) -> new Announcement(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getString("body"), rs.getTimestamp("sent_at").toInstant()),
                Timestamp.from(since), tenant.planType(), tenant.planType(), tenant.planType(), tenant.id());
    }

    private static final RowMapper<GlobalNotification> MAPPER = (rs, i) -> new GlobalNotification(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("body"),
            GlobalNotificationTarget.valueOf(rs.getString("target")),
            rs.getObject("target_tenant_id", UUID.class),
            rs.getString("tenant_name"),
            instant(rs, "scheduled_at"),
            GlobalNotificationStatus.valueOf(rs.getString("status")),
            integer(rs, "recipient_count"),
            integer(rs, "delivered_count"),
            integer(rs, "failure_count"),
            reason(rs.getString("failure_reason")),
            rs.getObject("created_by", UUID.class),
            instant(rs, "created_at"),
            instant(rs, "sent_at"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static GlobalNotificationFailureReason reason(String code) {
        if (code == null) return null;
        try {
            return GlobalNotificationFailureReason.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
