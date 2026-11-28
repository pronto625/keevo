package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.port.out.LastLoginQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcLastLoginQueryAdapter — P02: one indexed {@code MAX(occurred_at)} on the tenant {@code audit_log}
 * ({@code idx_audit_log_user_occurred (user_id, occurred_at DESC)}, O(log n)). The tenant table resolves through the
 * request search_path, as {@code JdbcEmployeeActivityRepository}.
 */
@Repository
public class JdbcLastLoginQueryAdapter implements LastLoginQueryPort {

    private static final String SQL =
            "SELECT MAX(occurred_at) FROM audit_log WHERE user_id = ? AND action = 'USER_AUTHENTICATED'";

    private final JdbcTemplate jdbc;

    public JdbcLastLoginQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> lastLoginAt(UUID userId) {
        Timestamp at = jdbc.queryForObject(SQL, Timestamp.class, userId);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }
}
