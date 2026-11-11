package com.keevo.identity.employee.adapter.out.persistence.impl;

import com.keevo.identity.employee.domain.model.MembershipInvitation;
import com.keevo.identity.employee.domain.model.MembershipInvitation.IdentifierKind;
import com.keevo.identity.employee.domain.model.MembershipInvitation.Status;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.6b — {@code public.membership_invitations} on PostgreSQL (V74): the conditional upsert's transitions, the
 * daily count, the lists' filters (expiry, store, company status), the row lock, cancel and the accepted kinds.
 * {@code @DataJpaTest} rolls each test back.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(JdbcMembershipInvitationRepository.class)
@DisplayName("19.6b — membership invitations on PostgreSQL")
class JdbcMembershipInvitationRepositoryPostgresTest extends PostgresIntegrationTest {

    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    static final Duration TTL = Duration.ofDays(7);

    @Autowired JdbcMembershipInvitationRepository repository;
    @Autowired JdbcTemplate jdbc;

    UUID tenant;
    UUID otherTenant;
    UUID user;
    UUID inviter;
    UUID store;

    @BeforeEach
    void setUp() {
        tenant = tenant("ACTIVE");
        otherTenant = tenant("ACTIVE");
        user = user();
        inviter = user();
        store = UUID.randomUUID();
    }

    private UUID tenant(String status) {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().replace("-", "").substring(0, 6);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, "
                + "?, ?)", id, "KV-" + suffix, "kv_i" + suffix, status, "Boutique " + suffix);
        return id;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, phone_number, password_hash, role, is_active, failed_attempts, "
                + "created_at, updated_at) VALUES (?, ?, 'h', 'OWNER', true, 0, now(), now())",
                id, "+2376" + Math.abs(id.getMostSignificantBits() % 100_000_000L));
        return id;
    }

    private MembershipInvitation invitation(UUID tenantId, UUID userId, Instant at) {
        return MembershipInvitation.pending(tenantId, userId, "EMPLOYEE", store, "Awa", "Mbarga",
                IdentifierKind.PHONE, "+237690000001", inviter, at, at.plus(TTL));
    }

    private boolean upsert(MembershipInvitation i, Instant now) {
        return repository.upsertPending(i, now, now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(1)));
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM public.membership_invitations WHERE tenant_id = ? "
                + "AND user_id = ?", String.class, tenant, user);
    }

    @Test
    @DisplayName("upsert: insert (message) → re-add of a live invitation renews what was typed, no message → expired "
            + "renewed with a message → cancel + re-add within 24 h: pending again, no message; after 24 h: message")
    void upsert_pendingTransitions() {
        assertThat(upsert(invitation(tenant, user, NOW), NOW)).isTrue();
        MembershipInvitation manager = MembershipInvitation.pending(tenant, user, "MANAGER", store, "Awa", "M",
                IdentifierKind.PHONE, "+237690000001", inviter, NOW.plusSeconds(60), NOW.plusSeconds(60).plus(TTL));
        assertThat(upsert(manager, NOW.plusSeconds(60))).isFalse();
        assertThat(jdbc.queryForObject("SELECT role FROM public.membership_invitations WHERE tenant_id = ? "
                + "AND user_id = ?", String.class, tenant, user)).isEqualTo("MANAGER");

        Instant later = NOW.plus(TTL).plusSeconds(120); // expired
        assertThat(upsert(invitation(tenant, user, later), later)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.membership_invitations WHERE tenant_id = ?",
                Integer.class, tenant)).isEqualTo(1); // one row per (company, person)

        UUID id = repository.findPendingForTenant(tenant, null, later).get(0).id();
        assertThat(repository.cancel(id, tenant, null, later)).isTrue();
        assertThat(status()).isEqualTo("CANCELLED");
        Instant soon = later.plusSeconds(600);
        assertThat(upsert(invitation(tenant, user, soon), soon)).isFalse(); // cancel/re-add loop: no new message
        assertThat(status()).isEqualTo("PENDING");
        Instant nextDay = later.plus(Duration.ofDays(1)).plusSeconds(1);
        assertThat(upsert(invitation(tenant, user, nextDay), nextDay)).isTrue();
    }

    @Test
    @DisplayName("upsert after a refusal: silent for 30 days, renewed after; after an acceptance (member removed): "
            + "renewed")
    void upsert_afterDeclineAndAccept() {
        upsert(invitation(tenant, user, NOW), NOW);
        UUID id = repository.findPendingForUser(user, NOW).get(0).invitation().id();
        assertThat(repository.markResponded(id, Status.DECLINED, NOW)).isTrue();
        assertThat(repository.markResponded(id, Status.ACCEPTED, NOW)).isFalse(); // no longer PENDING

        Instant in10Days = NOW.plus(Duration.ofDays(10));
        assertThat(upsert(invitation(tenant, user, in10Days), in10Days)).isFalse();
        assertThat(status()).isEqualTo("DECLINED");
        Instant in31Days = NOW.plus(Duration.ofDays(31));
        assertThat(upsert(invitation(tenant, user, in31Days), in31Days)).isTrue();
        assertThat(status()).isEqualTo("PENDING");

        repository.markResponded(id, Status.ACCEPTED, in31Days);
        Instant in33Days = NOW.plus(Duration.ofDays(33));
        assertThat(upsert(invitation(tenant, user, in33Days), in33Days)).isTrue();
        assertThat(status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("countInvitedSince counts the company's invitations created or renewed in the window")
    void countSince() {
        upsert(invitation(tenant, user, NOW), NOW);
        upsert(invitation(tenant, inviter, NOW.minus(Duration.ofDays(2))), NOW.minus(Duration.ofDays(2)));
        upsert(invitation(otherTenant, user, NOW), NOW);

        assertThat(repository.countInvitedSince(tenant, NOW.minus(Duration.ofDays(1)))).isEqualTo(1);
        assertThat(repository.countInvitedSince(tenant, NOW.minus(Duration.ofDays(3)))).isEqualTo(2);
    }

    @Test
    @DisplayName("company list: live PENDING only, optionally of one store; person's list: active companies only, "
            + "with the company")
    void lists() {
        upsert(invitation(tenant, user, NOW), NOW);
        UUID deleted = tenant("DELETED");
        upsert(invitation(deleted, user, NOW), NOW);

        assertThat(repository.findPendingForTenant(tenant, null, NOW)).hasSize(1);
        assertThat(repository.findPendingForTenant(tenant, store, NOW)).hasSize(1);
        assertThat(repository.pendingTenantSchema(repository.findPendingForTenant(tenant, null, NOW).get(0).id(), user,
                NOW)).get().asString().startsWith("kv_i");
        assertThat(repository.findPendingForTenant(tenant, UUID.randomUUID(), NOW)).isEmpty();
        assertThat(repository.findPendingForTenant(tenant, null, NOW.plus(TTL))).isEmpty(); // expired

        var mine = repository.findPendingForUser(user, NOW);
        assertThat(mine).singleElement().satisfies(p -> {
            assertThat(p.invitation().tenantId()).isEqualTo(tenant);
            assertThat(p.tenantName()).startsWith("Boutique ");
            assertThat(p.tenantSchema()).startsWith("kv_i");
            assertThat(p.invitation().identifierKind()).isEqualTo(IdentifierKind.PHONE);
            assertThat(p.invitation().invitedBy()).isEqualTo(inviter);
        });
        assertThat(repository.findPendingForUser(inviter, NOW)).isEmpty();
    }

    @Test
    @DisplayName("lock: only the person's own live invitation; cancel scoped to company and store")
    void lockAndCancel() {
        upsert(invitation(tenant, user, NOW), NOW);
        UUID id = repository.findPendingForUser(user, NOW).get(0).invitation().id();

        assertThat(repository.lockPendingForUser(id, user, NOW)).isPresent();
        assertThat(repository.lockPendingForUser(id, inviter, NOW)).isEmpty();
        assertThat(repository.lockPendingForUser(id, user, NOW.plus(TTL))).isEmpty();

        assertThat(repository.cancel(id, otherTenant, null, NOW)).isFalse();
        assertThat(repository.cancel(id, tenant, UUID.randomUUID(), NOW)).isFalse();
        assertThat(repository.cancel(id, tenant, store, NOW)).isTrue();
        assertThat(repository.cancel(id, tenant, store, NOW)).isFalse();
        assertThat(repository.lockPendingForUser(id, user, NOW)).isEmpty();
    }

    @Test
    @DisplayName("acceptedIdentifierKinds: accepted invitations of these users in this company only")
    void acceptedKinds() {
        upsert(invitation(tenant, user, NOW), NOW);
        upsert(MembershipInvitation.pending(tenant, inviter, "VIEWER", store, "B", "C", IdentifierKind.USERNAME,
                "b.c", null, NOW, NOW.plus(TTL)), NOW);
        UUID id = repository.findPendingForUser(inviter, NOW).get(0).invitation().id();
        repository.markResponded(id, Status.ACCEPTED, NOW);

        var kinds = repository.acceptedIdentifierKinds(tenant, List.of(user, inviter, UUID.randomUUID()));
        assertThat(kinds).containsOnlyKeys(inviter);
        assertThat(kinds.get(inviter)).isEqualTo(IdentifierKind.USERNAME);
        assertThat(repository.hasAccepted(tenant, inviter)).isTrue();
        assertThat(repository.hasAccepted(tenant, user)).isFalse();
        assertThat(repository.acceptedIdentifierKinds(otherTenant, List.of(inviter))).isEmpty();
        assertThat(repository.acceptedIdentifierKinds(tenant, List.of())).isEmpty();
    }
}
