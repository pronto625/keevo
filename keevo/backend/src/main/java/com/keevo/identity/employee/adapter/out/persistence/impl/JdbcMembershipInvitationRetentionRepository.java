package com.keevo.identity.employee.adapter.out.persistence.impl;

import com.keevo.identity.employee.domain.port.out.MembershipInvitationRetentionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Story 19.11c — the invitation purge over raw JDBC (fully-qualified table). One set-based {@code DELETE}: a DECLINED
 * or CANCELLED row carries its answer time in {@code responded_at}, a PENDING row has none (the renewal upsert clears
 * it), so {@code COALESCE(responded_at, expires_at)} is the instant from which the row is dead; the predicate is the
 * one of the partial index {@code idx_membership_invitations_retention} (V79).
 */
@Component
public class JdbcMembershipInvitationRetentionRepository implements MembershipInvitationRetentionRepository {

    static final String PURGE_SQL = "DELETE FROM public.membership_invitations "
            + "WHERE status <> 'ACCEPTED' AND COALESCE(responded_at, expires_at) < ?";

    private final JdbcTemplate jdbc;

    public JdbcMembershipInvitationRetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int purgeNeverMembershipsBefore(Instant cutoff) {
        return jdbc.update(PURGE_SQL, Timestamp.from(cutoff));
    }
}
