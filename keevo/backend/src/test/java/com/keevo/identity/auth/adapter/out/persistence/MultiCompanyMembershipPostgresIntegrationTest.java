package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.identity.auth.adapter.out.persistence.impl.RefreshTokenRepositoryAdapter;
import com.keevo.identity.auth.adapter.out.persistence.impl.UserMembershipRepositoryAdapter;
import com.keevo.identity.auth.domain.model.RefreshToken;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.6 — the three one-statement membership questions and the tenant-scoped refresh-token revocation on
 * PostgreSQL. {@code @DataJpaTest} rolls each test back; ids are random (no global counts).
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({UserMembershipRepositoryAdapter.class, RefreshTokenRepositoryAdapter.class})
@DisplayName("19.6 — multi-company memberships on PostgreSQL")
class MultiCompanyMembershipPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired UserMembershipRepositoryAdapter memberships;
    @Autowired RefreshTokenRepositoryAdapter refreshTokens;
    @Autowired EntityManager entityManager;

    private void member(UUID userId, UUID tenantId, String role, boolean active) {
        memberships.save(new UserTenantMembership(UUID.randomUUID(), userId, tenantId, role, active, Instant.now()));
    }

    @Test
    @DisplayName("hasActiveOwnerMembership: active OWNER only; an inactive OWNER or an EMPLOYEE does not count")
    void hasActiveOwnerMembership() {
        UUID owner = UUID.randomUUID();
        UUID inactiveOwner = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        member(owner, UUID.randomUUID(), "EMPLOYEE", true);
        member(owner, UUID.randomUUID(), "OWNER", true);
        member(inactiveOwner, UUID.randomUUID(), "OWNER", false);
        member(employee, UUID.randomUUID(), "EMPLOYEE", true);

        assertThat(memberships.hasActiveOwnerMembership(owner)).isTrue();
        assertThat(memberships.hasActiveOwnerMembership(inactiveOwner)).isFalse();
        assertThat(memberships.hasActiveOwnerMembership(employee)).isFalse();
        assertThat(memberships.hasActiveOwnerMembership(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("hasAnyMembership: any role, active or not")
    void hasAnyMembership() {
        UUID inactive = UUID.randomUUID();
        member(inactive, UUID.randomUUID(), "VIEWER", false);

        assertThat(memberships.hasAnyMembership(inactive)).isTrue();
        assertThat(memberships.hasAnyMembership(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("hasMembershipOutside: a membership in another company, active or not; this company does not count")
    void hasMembershipOutside() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID onlyHere = UUID.randomUUID();
        UUID shared = UUID.randomUUID();
        member(onlyHere, tenantA, "EMPLOYEE", true);
        member(shared, tenantA, "EMPLOYEE", true);
        member(shared, tenantB, "OWNER", false);

        assertThat(memberships.hasMembershipOutside(onlyHere, tenantA)).isFalse();
        assertThat(memberships.hasMembershipOutside(shared, tenantA)).isTrue();
        assertThat(memberships.hasMembershipOutside(shared, tenantB)).isTrue();
    }

    @Test
    @DisplayName("revokeAllByUserIdAndTenantId: only this user's live tokens of this account; other accounts and "
            + "users untouched")
    void revokeByUserAndTenant() {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Instant expires = Instant.now().plus(30, ChronoUnit.DAYS);
        RefreshToken inA = refreshTokens.save(RefreshToken.create(user, "kv_a_196", "h-" + UUID.randomUUID(), expires));
        RefreshToken inA2 = refreshTokens.save(RefreshToken.create(user, "kv_a_196", "h-" + UUID.randomUUID(), expires));
        RefreshToken inB = refreshTokens.save(RefreshToken.create(user, "kv_b_196", "h-" + UUID.randomUUID(), expires));
        RefreshToken otherInA = refreshTokens.save(
                RefreshToken.create(other, "kv_a_196", "h-" + UUID.randomUUID(), expires));
        entityManager.flush();

        assertThat(refreshTokens.revokeAllByUserIdAndTenantId(user, "kv_a_196")).isEqualTo(2);
        entityManager.clear();

        assertThat(refreshTokens.findById(inA.id()).orElseThrow().revoked()).isTrue();
        assertThat(refreshTokens.findById(inA2.id()).orElseThrow().revoked()).isTrue();
        assertThat(refreshTokens.findById(inB.id()).orElseThrow().revoked()).isFalse();
        assertThat(refreshTokens.findById(otherInA.id()).orElseThrow().revoked()).isFalse();
    }
}
