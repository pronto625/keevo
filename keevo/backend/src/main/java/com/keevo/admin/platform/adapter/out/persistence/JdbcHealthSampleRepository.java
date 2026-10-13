package com.keevo.admin.platform.adapter.out.persistence;

import com.keevo.admin.platform.domain.model.HealthSample;
import com.keevo.admin.platform.domain.port.out.HealthSampleRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JdbcHealthSampleRepository — {@code public.platform_health_samples} (Story 9-5). */
@Repository
public class JdbcHealthSampleRepository implements HealthSampleRepository {

    static final String INSERT_SQL =
            "INSERT INTO public.platform_health_samples (sampled_at, db_ok, requests_total, errors_5xx, "
            + "p50_ms, p95_ms, p99_ms, db_active_connections) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (sampled_at) DO NOTHING";

    private static final String COLUMNS =
            "sampled_at, db_ok, requests_total, errors_5xx, p50_ms, p95_ms, p99_ms, db_active_connections";

    private static final RowMapper<HealthSample> MAPPER = JdbcHealthSampleRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcHealthSampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(HealthSample s) {
        jdbc.update(INSERT_SQL, ps -> {
            ps.setTimestamp(1, Timestamp.from(s.sampledAt()));
            ps.setBoolean(2, s.dbOk());
            ps.setLong(3, s.requestsTotal());
            ps.setLong(4, s.errors5xx());
            setInt(ps, 5, s.p50Ms());
            setInt(ps, 6, s.p95Ms());
            setInt(ps, 7, s.p99Ms());
            setInt(ps, 8, s.dbActiveConnections());
        });
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM public.platform_health_samples WHERE sampled_at < ?", Timestamp.from(cutoff));
    }

    @Override
    public Optional<Instant> firstSampleAt() {
        Timestamp ts = jdbc.queryForObject("SELECT MIN(sampled_at) FROM public.platform_health_samples", Timestamp.class);
        return Optional.ofNullable(ts).map(Timestamp::toInstant);
    }

    @Override
    public long countOkSince(Instant since) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.platform_health_samples WHERE db_ok AND sampled_at >= ?",
                Long.class, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    @Override
    public Optional<HealthSample> latest() {
        return jdbc.query("SELECT " + COLUMNS + " FROM public.platform_health_samples "
                + "ORDER BY sampled_at DESC LIMIT 1", MAPPER).stream().findFirst();
    }

    @Override
    public List<HealthSample> recent(Instant since, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM public.platform_health_samples "
                + "WHERE sampled_at >= ? ORDER BY sampled_at DESC LIMIT ?", MAPPER, Timestamp.from(since), limit);
    }

    private static HealthSample map(ResultSet rs, int row) throws SQLException {
        return new HealthSample(
                rs.getTimestamp("sampled_at").toInstant(),
                rs.getBoolean("db_ok"),
                rs.getLong("requests_total"),
                rs.getLong("errors_5xx"),
                getInt(rs, "p50_ms"),
                getInt(rs, "p95_ms"),
                getInt(rs, "p99_ms"),
                getInt(rs, "db_active_connections"));
    }

    private static Integer getInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static void setInt(java.sql.PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER);
        else ps.setInt(idx, v);
    }
}
