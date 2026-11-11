package com.keevo.shared.infrastructure.security;

import com.keevo.shared.module.application.KeevoModuleDirectory;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.ModulePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 16.13 (reprise, user decision D2) — the single purchase-price policy over the SHIPPED manifests: OWNER and
 * MANAGER see purchase prices, EMPLOYEE, VIEWER, SUPER_ADMIN and anonymous do not.
 */
@DisplayName("PurchasePriceVisibility — OWNER and MANAGER only (Story 16.13 reprise)")
class PurchasePriceVisibilityTest {

    private static PurchasePriceVisibility policy;

    @BeforeAll
    static void shippedManifests() {
        ModuleCatalog catalog = new ModuleCatalog(new PathMatchingResourcePatternResolver());
        ModulePermissions permissions = new ModulePermissions(catalog, KeevoModuleDirectory.empty(catalog));
        permissions.afterSingletonsInstantiated();
        policy = new PurchasePriceVisibility(
                new StaticListableBeanFactory(Map.of("modulePermissions", permissions))
                        .getBeanProvider(ModulePermissions.class));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void roleMatrix() {
        assertThat(policy.canView(List.of("OWNER"))).isTrue();
        assertThat(policy.canView(List.of("MANAGER"))).isTrue();
        assertThat(policy.canView(List.of("EMPLOYEE"))).isFalse();
        assertThat(policy.canView(List.of("VIEWER"))).isFalse();
        assertThat(policy.canView(List.of("SUPER_ADMIN"))).isFalse();
        assertThat(policy.canView(List.of())).isFalse();
        assertThat(policy.canView((List<String>) null)).isFalse();
    }

    @Test
    void currentActor() {
        assertThat(policy.currentActorCanView()).as("anonymous").isFalse();
        as("MANAGER");
        assertThat(policy.currentActorCanView()).isTrue();
        as("EMPLOYEE");
        assertThat(policy.currentActorCanView()).isFalse();
        as("OWNER");
        assertThat(policy.currentActorCanView()).isTrue();
    }

    @Test
    void withoutRoleMappingOnlyOwner() {
        PurchasePriceVisibility bare = PurchasePriceVisibility.withoutRoleMapping();
        assertThat(bare.canView(List.of("OWNER"))).isTrue();
        assertThat(bare.canView(List.of("MANAGER"))).isFalse();
        assertThat(bare.canView(List.of("EMPLOYEE"))).isFalse();
    }
}
