package com.keevo.identity.employee.application.service;

import com.keevo.identity.employee.adapter.out.persistence.impl.JdbcMembershipInvitationRetentionRepository;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 19.11c (routed from review 19-6b) — the nightly purge of invitations that will never become a membership,
 * on PostgreSQL: DECLINED / CANCELLED answered and PENDING expired more than 90 days ago are deleted in one statement;
 * ACCEPTED rows and recent rows are kept.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Invitation retention (19.11c) on PostgreSQL")
class MembershipInvitationRetentionPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:55:00Z");

    @Autowired JdbcTemplate jdbc;

    private final List<UUID> tenants = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID t : tenants) jdbc.update("DELETE FROM public.membership_invitations WHERE tenant_id = ?", t);
        for (UUID u : users) jdbc.update("DELETE FROM public.users WHERE id = ?", u);
        for (UUID t : tenants) jdbc.update("DELETE FROM public.tenants WHERE id = ?", t);
    }

    @Test
    @DisplayName("refused, cancelled and expired invitations older than 90 days are deleted; accepted and recent kept")
    void purge_neverMemberships() {
        UUID tenant = tenant();
        Instant old = NOW.minus(Duration.ofDays(91));
        Instant recent = NOW.minus(Duration.ofDays(89));
        UUID declinedOld = invitation(tenant, "DECLINED", old.minus(Duration.ofDays(1)), old);
        UUID cancelledOld = invitation(tenant, "CANCELLED", old.minus(Duration.ofDays(1)), old);
        UUID expiredPending = invitation(tenant, "PENDING", old, null);
        UUID acceptedOld = invitation(tenant, "ACCEPTED", old.minus(Duration.ofDays(1)), old);
        UUID declinedRecent = invitation(tenant, "DECLINED", recent.plus(Duration.ofDays(7)), recent);
        UUID pendingAlive = invitation(tenant, "PENDING", NOW.plus(Duration.ofDays(5)), null);

        MembershipInvitationRetentionJob job = new MembershipInvitationRetentionJob(
                new JdbcMembershipInvitationRetentionRepository(jdbc), Clock.fixed(NOW, ZoneOffset.UTC), 90);

        assertThat(job.purge()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT id FROM public.membership_invitations WHERE tenant_id = ?", UUID.class,
                tenant)).containsExactlyInAnyOrder(acceptedOld, declinedRecent, pendingAlive)
                .doesNotContain(declinedOld, cancelledOld, expiredPending);
        assertThat(job.purge()).isZero();
    }

    @Test
    @DisplayName("a retention shorter than the 30-day refusal block is refused at start-up")
    void retention_neverShorterThanTheRefusalBlock() {
        assertThatThrownBy(() -> new MembershipInvitationRetentionJob(
                new JdbcMembershipInvitationRetentionRepository(jdbc), Clock.systemUTC(), 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().replace("-", "").substring(0, 6);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) "
                + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', 'Boutique Invitations')",
                id, "KV-" + suffix.toUpperCase(), "kv_" + suffix);
        tenants.add(id);
        return id;
    }

    /** An invitation of a new person in {@code tenant}; returns its id. */
    private UUID invitation(UUID tenant, String status, Instant expiresAt, Instant respondedAt) {
        UUID user = UUID.randomUUID();
        users.add(user);
        jdbc.update("INSERT INTO public.users (id, phone_number, password_hash, role, is_active, failed_attempts, "
                + "created_at, updated_at) VALUES (?, ?, 'h', 'EMPLOYEE', true, 0, now(), now())",
                user, "+2376" + Math.abs(user.getMostSignificantBits() % 100_000_000L));
        UUID id = UUID.randomUUID();
        Instant invitedAt = expiresAt.minus(Duration.ofDays(7));
        jdbc.update("INSERT INTO public.membership_invitations (id, tenant_id, user_id, role, store_id, first_name, "
                        + "last_name, identifier_kind, identifier, status, invited_at, expires_at, responded_at, "
                        + "last_sent_at) VALUES (?, ?, ?, 'EMPLOYEE', ?, 'Awa', 'Diallo', 'PHONE', '+237690000000', ?, "
                        + "?, ?, ?, ?)",
                id, tenant, user, UUID.randomUUID(), status, Timestamp.from(invitedAt), Timestamp.from(expiresAt),
                respondedAt == null ? null : Timestamp.from(respondedAt), Timestamp.from(invitedAt));
        return id;
    }
}
