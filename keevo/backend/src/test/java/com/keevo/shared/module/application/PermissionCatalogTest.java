package com.keevo.shared.module.application;

import com.keevo.shared.infrastructure.security.ShippedActorPermissions;
import com.keevo.shared.module.EntitlementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story 19.14 — the catalogue of permissions an owner may give a role: every shipped permission has a French label,
 * only the usable modules' permissions are offered, the finance summary stays the owner's.
 */
@DisplayName("PermissionCatalog — grantable permissions with French labels (Story 19.14)")
class PermissionCatalogTest {

    private final ModulePermissions permissions = ShippedActorPermissions.modulePermissions();
    private final ModuleCatalog catalog = new ModuleCatalog(new PathMatchingResourcePatternResolver());
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final PermissionCatalog permissionCatalog = new PermissionCatalog(permissions, catalog, entitlements);

    @Test
    @DisplayName("every permission declared by a shipped manifest has a French label (never a code on screen)")
    void everyShippedPermissionIsLabelled() {
        for (String code : permissions.declared()) {
            assertThat(PermissionCatalog.label(code)).as(code).isNotBlank();
        }
    }

    @Test
    @DisplayName("grouped by usable module, labelled, finance grantable (reprise 11/11/2026), read flag set")
    void grantableOfUsableModules() {
        when(entitlements.enabledModules("kv_test")).thenReturn(Set.of("core", "pos", "catalog", "finance"));

        List<PermissionCatalog.Group> groups = permissionCatalog.grantable("kv_test");

        List<String> codes = groups.stream().flatMap(g -> g.permissions().stream()).map(PermissionCatalog.Entry::code)
                .toList();
        assertThat(codes).contains("SALE_CREATE", "STORE_SALES_VIEW", "PURCHASE_PRICE_VIEW", "TEAM_MANAGE",
                "FINANCE_VIEW").doesNotContain("EXPENSE_VIEW", "INVOICE_VIEW");
        assertThat(groups).allSatisfy(g -> assertThat(g.moduleLabel()).isNotBlank());
        PermissionCatalog.Entry salesView = groups.stream().flatMap(g -> g.permissions().stream())
                .filter(e -> e.code().equals("STORE_SALES_VIEW")).findFirst().orElseThrow();
        assertThat(salesView.label()).isEqualTo("Voir les ventes de toute la boutique");
        assertThat(salesView.readOnly()).isTrue();
    }

    @Test
    void grantableChecks() {
        when(entitlements.enabledModules("kv_test")).thenReturn(Set.of("core", "pos"));

        assertThat(permissionCatalog.isGrantable("kv_test", "SALE_CREATE")).isTrue();
        assertThat(permissionCatalog.isGrantable("kv_test", "EXPENSE_VIEW")).isFalse(); // module not usable
        assertThat(permissionCatalog.isGrantable("FINANCE_VIEW")).isTrue();               // reprise 11/11/2026
        assertThat(permissionCatalog.isGrantable("NOT_A_PERMISSION")).isFalse();
        assertThat(permissionCatalog.isGrantable((String) null)).isFalse();
    }
}
