package com.keevo.shared.infrastructure.security;

import com.keevo.shared.infrastructure.security.access.AccessState;
import com.keevo.shared.module.CustomRoleGrant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 19.14 — the custom role carried by the authentication, resolved from the access state by the JWT filter. */
@DisplayName("JwtAuthFilter — custom role grant resolution (Story 19.14)")
class CustomRoleGrantResolutionTest {

    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID STORE_B = UUID.randomUUID();

    private static AccessState state(String baseRole, List<UUID> stores) {
        return new AccessState("EMPLOYEE", true, "ACTIVE", null, null, Set.of(),
                new AccessState.CustomRoleState(UUID.randomUUID(), "Caissier", baseRole, Set.of("SALE_CREATE"), stores));
    }

    @Test
    void noCustomRole_noGrant() {
        assertThat(JwtAuthFilter.customRoleGrant(new AccessState("EMPLOYEE", true, "ACTIVE", null, null, Set.of()),
                "EMPLOYEE", STORE_A)).isNull();
    }

    @Test
    void applicableRole_grantsItsPermissions() {
        CustomRoleGrant grant = JwtAuthFilter.customRoleGrant(state("EMPLOYEE", null), "EMPLOYEE", STORE_A);
        assertThat(grant.roleName()).isEqualTo("Caissier");
        assertThat(grant.permissions()).containsExactly("SALE_CREATE");
        assertThat(JwtAuthFilter.customRoleGrant(state("EMPLOYEE", List.of(STORE_A)), "EMPLOYEE", STORE_A)
                .permissions()).containsExactly("SALE_CREATE");
    }

    @Test
    @DisplayName("store outside the role's stores, or base role ≠ membership role → an empty grant (fail closed)")
    void notApplicable_emptyGrant() {
        assertThat(JwtAuthFilter.customRoleGrant(state("EMPLOYEE", List.of(STORE_B)), "EMPLOYEE", STORE_A)
                .permissions()).isEmpty();
        assertThat(JwtAuthFilter.customRoleGrant(state("EMPLOYEE", List.of(STORE_B)), "EMPLOYEE", null)
                .permissions()).isEmpty();
        assertThat(JwtAuthFilter.customRoleGrant(state("MANAGER", null), "EMPLOYEE", STORE_A)
                .permissions()).isEmpty();
    }
}
