package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.model.OtpChallenge;
import com.keevo.identity.auth.domain.port.out.OtpChallengeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.2 — {@code public.otp_challenges} over raw JDBC (fully-qualified table). One statement per method; the
 * {@code FOR UPDATE} lock and the guarded increment are clearer in SQL than in JPA.
 */
@Component
public class JdbcOtpChallengeRepository implements OtpChallengeRepository {

    static final String INVALIDATE_SQL =
            "UPDATE public.otp_challenges SET consumed_at = ? WHERE identifier = ? AND consumed_at IS NULL";
    static final String INSERT_SQL =
            "INSERT INTO public.otp_challenges (id, identifier, code_hash, expires_at, attempts, created_at) "
            + "VALUES (?, ?, ?, ?, 0, ?)";
    static final String LOCK_LATEST_SQL =
            "SELECT id, code_hash, attempts FROM public.otp_challenges "
            + "WHERE identifier = ? AND consumed_at IS NULL AND expires_at > ? "
            + "ORDER BY created_at DESC LIMIT 1 FOR UPDATE";
    static final String RECORD_FAILURE_SQL =
            "UPDATE public.otp_challenges SET attempts = attempts + 1, "
            + "consumed_at = CASE WHEN attempts + 1 >= CAST(? AS integer) THEN CAST(? AS timestamptz) ELSE consumed_at END "
            + "WHERE id = ? RETURNING attempts";
    static final String CONSUME_SQL =
            "UPDATE public.otp_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL";
    static final String SUM_FAILURES_SQL =
            "SELECT COALESCE(SUM(attempts), 0) FROM public.otp_challenges WHERE identifier = ? AND created_at >= ?";
    static final String PURGE_SQL = "DELETE FROM public.otp_challenges WHERE created_at < ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcOtpChallengeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int invalidateActive(String identifier, Instant now) {
        return jdbcTemplate.update(INVALIDATE_SQL, Timestamp.from(now), identifier);
    }

    @Override
    public void insert(UUID id, String identifier, String codeHash, Instant expiresAt, Instant createdAt) {
        jdbcTemplate.update(INSERT_SQL, id, identifier, codeHash, Timestamp.from(expiresAt), Timestamp.from(createdAt));
    }

    @Override
    public Optional<OtpChallenge> lockLatestActive(String identifier, Instant now) {
        List<OtpChallenge> rows = jdbcTemplate.query(LOCK_LATEST_SQL,
                (rs, n) -> new OtpChallenge((UUID) rs.getObject("id"), rs.getString("code_hash"),
                        rs.getInt("attempts")),
                identifier, Timestamp.from(now));
        return rows.stream().findFirst();
    }

    @Override
    public int recordFailure(UUID id, int maxAttempts, Instant now) {
        Integer attempts = jdbcTemplate.queryForObject(RECORD_FAILURE_SQL, Integer.class,
                maxAttempts, Timestamp.from(now), id);
        return attempts == null ? maxAttempts : attempts;
    }

    @Override
    public void consume(UUID id, Instant now) {
        jdbcTemplate.update(CONSUME_SQL, Timestamp.from(now), id);
    }

    @Override
    public int sumFailuresSince(String identifier, Instant since) {
        Integer sum = jdbcTemplate.queryForObject(SUM_FAILURES_SQL, Integer.class, identifier, Timestamp.from(since));
        return sum == null ? 0 : sum;
    }

    @Override
    public int purgeCreatedBefore(Instant cutoff) {
        return jdbcTemplate.update(PURGE_SQL, Timestamp.from(cutoff));
    }
}
