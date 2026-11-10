package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.port.out.AppleRevocationQueue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Story 19.3b — {@code public.apple_token_revocations}: due rows read on the {@code next_attempt_at} index, outcomes
 * written set-based ({@code = ANY(?)} on the primary key).
 */
@Component
public class JdbcAppleRevocationQueue implements AppleRevocationQueue {

    static final String FIND_DUE_SQL =
            "SELECT id, authenticator_id, client_id, token, attempts FROM public.apple_token_revocations "
            + "WHERE next_attempt_at <= now() ORDER BY next_attempt_at LIMIT ?";
    static final String DELETE_SQL = "DELETE FROM public.apple_token_revocations WHERE id = ANY(?::uuid[])";
    /** Backoff: 2^attempts minutes after this failure, at most one day. */
    static final String RECORD_FAILURES_SQL =
            "UPDATE public.apple_token_revocations SET attempts = attempts + 1, "
            + "next_attempt_at = now() + LEAST(interval '1 minute' * power(2, LEAST(attempts + 1, 20)), interval '1 day') "
            + "WHERE id = ANY(?::uuid[])";
    static final String GIVE_UP_SQL =
            "DELETE FROM public.apple_token_revocations WHERE id = ANY(?::uuid[]) AND attempts >= ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcAppleRevocationQueue(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<PendingRevocation> findDue(int limit) {
        return jdbcTemplate.query(FIND_DUE_SQL, (rs, n) -> new PendingRevocation(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("authenticator_id"),
                rs.getString("client_id"),
                rs.getBytes("token"),
                rs.getInt("attempts")), limit);
    }

    @Override
    public int delete(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(DELETE_SQL, (Object) ids.toArray(new UUID[0]));
    }

    @Override
    public int recordFailures(Collection<UUID> ids, int maxAttempts) {
        if (ids.isEmpty()) {
            return 0;
        }
        UUID[] array = ids.toArray(new UUID[0]);
        jdbcTemplate.update(RECORD_FAILURES_SQL, (Object) array);
        return jdbcTemplate.update(GIVE_UP_SQL, array, maxAttempts);
    }
}
