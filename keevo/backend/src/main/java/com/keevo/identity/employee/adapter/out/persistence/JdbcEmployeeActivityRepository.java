package com.keevo.identity.employee.adapter.out.persistence;

import com.keevo.identity.employee.domain.port.out.EmployeeActivityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JdbcEmployeeActivityRepository — SQL adapter of the FR67 activity read model (v1s-14-6).
 *
 * <p>One query over existing tables: tenant {@code audit_log} (indexed by
 * {@code (user_id, occurred_at)}; {@code USER_AUTHENTICATED} = login), tenant {@code sales}
 * ({@code employee_id} = user id; COMPLETED only for the counts, any status for « last
 * activity ») and {@code public.refresh_tokens} filtered on the current tenant schema. Tenant
 * tables resolve through the request search_path (same as JdbcSellerLeaderboardRepository).
 */
@Repository
public class JdbcEmployeeActivityRepository implements EmployeeActivityRepository {

    private static final String SQL =
        "WITH ids AS (SELECT DISTINCT unnest(?::uuid[]) AS user_id), " +
        "sold AS (" +
        "  SELECT s.employee_id AS user_id," +
        "    COUNT(*) FILTER (WHERE s.status = 'COMPLETED' AND s.occurred_at >= ? AND s.occurred_at <= ?)::int AS today_count," +
        "    COALESCE(SUM(s.total_amount) FILTER (WHERE s.status = 'COMPLETED' AND s.occurred_at >= ? AND s.occurred_at <= ?), 0)::bigint AS today_amount," +
        "    COUNT(*) FILTER (WHERE s.status = 'COMPLETED' AND s.occurred_at >= ? AND s.occurred_at <= ?)::int AS week_count," +
        "    COALESCE(SUM(s.total_amount) FILTER (WHERE s.status = 'COMPLETED' AND s.occurred_at >= ? AND s.occurred_at <= ?), 0)::bigint AS week_amount," +
        "    MAX(s.occurred_at) FILTER (WHERE s.occurred_at <= ?) AS last_sale_at" +
        "  FROM sales s WHERE s.employee_id IN (SELECT user_id FROM ids)" +
        "  GROUP BY s.employee_id" +
        ") " +
        "SELECT ids.user_id," +
        "  (SELECT MAX(a.occurred_at) FROM audit_log a" +
        "     WHERE a.user_id = ids.user_id AND a.action = 'USER_AUTHENTICATED') AS last_login_at," +
        "  (SELECT MAX(a.occurred_at) FROM audit_log a WHERE a.user_id = ids.user_id) AS last_audit_at," +
        "  sold.last_sale_at," +
        "  COALESCE(sold.today_count, 0) AS today_count," +
        "  COALESCE(sold.today_amount, 0) AS today_amount," +
        "  COALESCE(sold.week_count, 0) AS week_count," +
        "  COALESCE(sold.week_amount, 0) AS week_amount," +
        "  EXISTS (SELECT 1 FROM public.refresh_tokens r" +
        "     WHERE r.user_id = ids.user_id AND r.tenant_id = ? AND r.revoked = FALSE" +
        "       AND r.expires_at > ?) AS active_session " +
        "FROM ids LEFT JOIN sold ON sold.user_id = ids.user_id";

    private final JdbcTemplate jdbc;

    public JdbcEmployeeActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, RawActivity> findRawActivity(Collection<UUID> userIds, Instant todayStart,
                                                  Instant weekStart, Instant now, String tenantSchema) {
        Map<UUID, RawActivity> result = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        String idArray = new LinkedHashSet<>(userIds).stream()
                .map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}"));
        Timestamp today = Timestamp.from(todayStart);
        Timestamp week = Timestamp.from(weekStart);
        Timestamp end = Timestamp.from(now);

        jdbc.query(SQL, rs -> {
            RawActivity row = map(rs);
            result.put(row.userId(), row);
        }, idArray,
                today, end, today, end,
                week, end, week, end, end,
                tenantSchema, end);
        return result;
    }

    private static RawActivity map(ResultSet rs) throws SQLException {
        return new RawActivity(
                UUID.fromString(rs.getString("user_id")),
                instant(rs.getTimestamp("last_login_at")),
                instant(rs.getTimestamp("last_audit_at")),
                instant(rs.getTimestamp("last_sale_at")),
                rs.getInt("today_count"),
                rs.getLong("today_amount"),
                rs.getInt("week_count"),
                rs.getLong("week_amount"),
                rs.getBoolean("active_session"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
