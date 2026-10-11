package com.keevo.shared.infrastructure.security;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** v1s-15-9: the single RBAC / actor helper. */
class CurrentActorTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void auth(Object principal, Object details, String... roles) {
        var token = new UsernamePasswordAuthenticationToken(principal, null,
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        token.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static void assertForbidden(Runnable call, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(message)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    // ── actorId ────────────────────────────────────────────────────────

    @Test
    void actorId_uuidPrincipal() {
        auth(USER, null, "OWNER");
        assertThat(CurrentActor.actorId()).isEqualTo(USER);
    }

    @Test
    void actorId_stringPrincipalIsParsed() {
        auth(USER.toString(), null, "OWNER");
        assertThat(CurrentActor.actorId()).isEqualTo(USER);
    }

    @Test
    void actorId_withoutAuthentication_isForbidden() {
        assertForbidden(CurrentActor::actorId, "No authenticated actor");
    }

    // ── roles ──────────────────────────────────────────────────────────

    @Test
    void roles_unauthenticated_areAllFalseAndRoleNull() {
        assertThat(CurrentActor.authentication()).isNull();
        assertThat(CurrentActor.isOwner()).isFalse();
        assertThat(CurrentActor.isEmployee()).isFalse();
        assertThat(CurrentActor.isSuperAdmin()).isFalse();
        assertThat(CurrentActor.role()).isNull();
    }

    @Test
    void role_priorityOwnerThenEmployeeThenSuperAdmin() {
        auth(USER, null, "EMPLOYEE", "OWNER");
        assertThat(CurrentActor.role()).isEqualTo("OWNER");
        auth(USER, null, "SUPER_ADMIN", "EMPLOYEE");
        assertThat(CurrentActor.role()).isEqualTo("EMPLOYEE");
        auth(USER, null, "SUPER_ADMIN");
        assertThat(CurrentActor.role()).isEqualTo("SUPER_ADMIN");
        assertThat(CurrentActor.isSuperAdmin()).isTrue();
        auth(USER, null, "SOMETHING_ELSE");
        assertThat(CurrentActor.role()).isNull();
    }

    @Test
    void requireOwner_passesForOwner_forbiddenOtherwise() {
        auth(USER, null, "OWNER");
        CurrentActor.requireOwner("owner only");

        auth(USER, AuthDetails.employee("Awa", STORE), "EMPLOYEE");
        assertForbidden(() -> CurrentActor.requireOwner("owner only"), "owner only");

        SecurityContextHolder.clearContext();
        assertForbidden(() -> CurrentActor.requireOwner("owner only"), "owner only");
    }

    @Test
    void requireSuperAdmin_passesForSuperAdmin_forbiddenOtherwise() {
        auth(USER, null, "SUPER_ADMIN");
        CurrentActor.requireSuperAdmin();

        auth(USER, null, "OWNER");
        assertForbidden(CurrentActor::requireSuperAdmin, "SUPER_ADMIN role required");

        SecurityContextHolder.clearContext();
        assertForbidden(CurrentActor::requireSuperAdmin, "SUPER_ADMIN role required");
    }

    // ── assignedStoreId (fail closed) ──────────────────────────────────

    @Test
    void assignedStoreId_withoutAuthentication_failsClosed() {
        assertForbidden(CurrentActor::assignedStoreId, "No authenticated actor");
    }

    @Test
    void assignedStoreId_employeeWithStore_returnsStore() {
        auth(USER, AuthDetails.employee("Awa", STORE), "EMPLOYEE");
        assertThat(CurrentActor.assignedStoreId()).isEqualTo(STORE);
    }

    @Test
    void assignedStoreId_employeeWithoutStore_failsClosed() {
        auth(USER, AuthDetails.owner("Awa"), "EMPLOYEE");
        assertForbidden(CurrentActor::assignedStoreId, "EMPLOYEE token missing assigned store");

        auth(USER, null, "EMPLOYEE");
        assertForbidden(CurrentActor::assignedStoreId, "EMPLOYEE token missing assigned store");
    }

    @Test
    void assignedStoreId_owner_isNull() {
        auth(USER, AuthDetails.owner("Moussa"), "OWNER");
        assertThat(CurrentActor.assignedStoreId()).isNull();
        auth(USER, "not-auth-details", "OWNER");
        assertThat(CurrentActor.assignedStoreId()).isNull();
    }

    // ── actorLabel ─────────────────────────────────────────────────────

    @Test
    void actorLabel_usesFirstName() {
        auth(USER, AuthDetails.employee("Awa", STORE), "EMPLOYEE");
        assertThat(CurrentActor.actorLabel()).isEqualTo("Awa");
    }

    @Test
    void actorLabel_employeeWithoutFirstName_fallsBackNeverUuid() {
        auth(USER, AuthDetails.employee(null, STORE), "EMPLOYEE");
        assertThat(CurrentActor.actorLabel()).isEqualTo("Un employé").doesNotContain(USER.toString());
        auth(USER, AuthDetails.employee("   ", STORE), "EMPLOYEE");
        assertThat(CurrentActor.actorLabel()).isEqualTo("Un employé");
    }

    @Test
    void actorLabel_ownerWithoutFirstName_fallsBack() {
        auth(USER, null, "OWNER");
        assertThat(CurrentActor.actorLabel()).isEqualTo("Le propriétaire");
    }

    @Test
    void actorLabel_otherOrUnauthenticated_fallsBack() {
        auth(USER, null, "SUPER_ADMIN");
        assertThat(CurrentActor.actorLabel()).isEqualTo("Un utilisateur");
        SecurityContextHolder.clearContext();
        assertThat(CurrentActor.actorLabel()).isEqualTo("Un utilisateur");
        assertThat(List.of(CurrentActor.actorLabel())).doesNotContain(USER.toString());
    }
}
