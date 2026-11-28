package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.port.out.LastLoginQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcLastLoginQueryAdapter — P02: the latest sign-in of a user in the tenant {@code audit_log}, read as the first entry of
 * the partial index {@code idx_audit_log_user_login (user_id, occurred_at DESC) WHERE action = 'USER_AUTHENTICATED'}
 * (core V5): one index descent, O(log n), whatever the number of other audit rows of the user. The tenant table resolves
 * through the request search_path, as {@code JdbcEmployeeActivityRepository}.
 */
@Repository
public class JdbcLastLoginQueryAdapter implements LastLoginQueryPort {

    private static final String SQL =
            "SELECT occurred_at FROM audit_log WHERE user_id = ? AND action = 'USER_AUTHENTICATED' "
                    + "ORDER BY occurred_at DESC LIMIT 1";

    private final JdbcTemplate jdbc;

    public JdbcLastLoginQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> lastLoginAt(UUID userId) {
        return jdbc.queryForList(SQL, Timestamp.class, userId).stream().findFirst().map(Timestamp::toInstant);
    }
}
