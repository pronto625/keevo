package com.keevo.shared.module.application;

import com.keevo.identity.api.Role;
import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.LoadedModuleManifest;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.TestModuleCatalogs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModulePermissions — role mapping of the module permissions (Stories 14.1, 19.5)")
class ModulePermissionsTest {

    private static final List<String> INVOICING = List.of("INVOICE_CREATE", "INVOICE_VIEW", "INVOICE_MANAGE");

    private static Map<String, List<String>> roles(List<String> manager, List<String> employee, List<String> viewer) {
        Map<String, List<String>> roles = new LinkedHashMap<>();
        if (manager != null) {
            roles.put("MANAGER", manager);
        }
        if (employee != null) {
            roles.put("EMPLOYEE", employee);
        }
        if (viewer != null) {
            roles.put("VIEWER", viewer);
        }
        return roles;
    }

    private static ModuleCatalog catalog(Map<String, List<String>> invoicingRoles) {
        return new ModuleCatalog(List.of(TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true),
                TestModuleCatalogs.withPermissions("invoicing", ModuleCategory.PREMIUM, false, INVOICING,
                        invoicingRoles),
                TestModuleCatalogs.withPermissions("crm", ModuleCategory.BUSINESS, true, List.of("CLIENT_EXPORT"))));
    }

    private static ModuleCatalog validCatalog() {
        return catalog(roles(List.of("INVOICE_VIEW", "INVOICE_CREATE"), List.of("INVOICE_VIEW", "INVOICE_CREATE"),
                List.of("INVOICE_VIEW")));
    }

    private static KeevoModule module(String id, Map<Role, Set<String>> code) {
        return new KeevoModule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> permissionsFor(Role role) {
                return code.get(role);
            }
        };
    }

    private static ModulePermissions permissions(ModuleCatalog catalog) {
        return new ModulePermissions(catalog, KeevoModuleDirectory.empty(catalog));
    }

    @Test
    @DisplayName("OWNER: every declared permission; MANAGER/EMPLOYEE/VIEWER: their manifest entries; SUPER_ADMIN: nothing")
    void mapping() {
        ModulePermissions permissions = permissions(validCatalog());
        assertThat(permissions.permissionsOf(List.of("OWNER")))
                .containsExactlyInAnyOrder("INVOICE_CREATE", "INVOICE_VIEW", "INVOICE_MANAGE", "CLIENT_EXPORT");
        assertThat(permissions.permissionsOf(List.of("EMPLOYEE"))).containsExactlyInAnyOrder("INVOICE_VIEW", "INVOICE_CREATE");
        assertThat(permissions.permissionsOf(List.of("MANAGER"))).containsExactlyInAnyOrder("INVOICE_VIEW", "INVOICE_CREATE");
        assertThat(permissions.permissionsOf(List.of("VIEWER"))).containsExactly("INVOICE_VIEW");
        assertThat(permissions.permissionsFor(Role.VIEWER)).containsExactly("INVOICE_VIEW");
        assertThat(permissions.permissionsOf(List.of("SUPER_ADMIN"))).isEmpty();
        assertThat(permissions.permissionsOf(List.of("GHOST"))).isEmpty();
        assertThat(permissions.permissionsOf(List.of())).isEmpty();
        assertThat(permissions.isDeclared("INVOICE_MANAGE")).isTrue();
        assertThat(permissions.isDeclared("GHOST")).isFalse();
    }

    @Test
    @DisplayName("a module bean stating the same sets as its manifest boots")
    void codeMatchesManifest() {
        ModuleCatalog catalog = validCatalog();
        ModulePermissions permissions = new ModulePermissions(catalog, KeevoModuleDirectory.of(catalog,
                List.of(module("invoicing", Map.of(Role.MANAGER, Set.of("INVOICE_VIEW", "INVOICE_CREATE"),
                        Role.EMPLOYEE, Set.of("INVOICE_CREATE", "INVOICE_VIEW"), Role.VIEWER, Set.of("INVOICE_VIEW"))))));
        permissions.afterSingletonsInstantiated();
        assertThat(permissions.permissionsOf(List.of("EMPLOYEE"))).containsExactlyInAnyOrder("INVOICE_VIEW", "INVOICE_CREATE");
    }

    @Test
    @DisplayName("boot fails when a module bean's code-side set differs from its manifest")
    void codeDiffersFromManifest() {
        ModuleCatalog catalog = validCatalog();
        ModulePermissions permissions = new ModulePermissions(catalog, KeevoModuleDirectory.of(catalog,
                List.of(module("invoicing", Map.of(Role.EMPLOYEE, Set.of("INVOICE_VIEW"))))));
        assertThatThrownBy(permissions::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invoicing").hasMessageContaining("EMPLOYEE");
    }

    @Test
    @DisplayName("boot fails when a manifest lacks an explicit entry for VIEWER")
    void missingViewerEntry() {
        ModulePermissions permissions = permissions(catalog(roles(List.of(), List.of("INVOICE_VIEW"), null)));
        assertThatThrownBy(permissions::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invoicing").hasMessageContaining("VIEWER");
    }

    @Test
    @DisplayName("boot fails when a manifest lacks an explicit entry for MANAGER")
    void missingManagerEntry() {
        ModulePermissions permissions = permissions(catalog(roles(null, List.of(), List.of())));
        assertThatThrownBy(permissions::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MANAGER");
    }

    @Test
    @DisplayName("boot fails when a role lists a permission its manifest does not declare")
    void undeclaredPermission() {
        ModulePermissions permissions = permissions(catalog(roles(List.of(), List.of("CLIENT_EXPORT"), List.of())));
        assertThatThrownBy(permissions::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invoicing").hasMessageContaining("CLIENT_EXPORT");
    }

    @Test
    @DisplayName("boot fails when VIEWER is granted a write permission")
    void viewerWritePermission() {
        ModulePermissions permissions = permissions(catalog(roles(List.of(), List.of(),
                List.of("INVOICE_VIEW", "INVOICE_CREATE"))));
        assertThatThrownBy(permissions::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVOICE_CREATE").hasMessageContaining("VIEWER");
    }

    @Test
    @DisplayName("boot fails when a manifest declares permissions for OWNER or an unknown role")
    void unknownRoleEntry() {
        Map<String, List<String>> roles = roles(List.of(), List.of(), List.of());
        roles.put("OWNER", List.of("INVOICE_MANAGE"));
        ModulePermissions permissions = permissions(catalog(roles));
        assertThatThrownBy(permissions::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OWNER");
    }

    @Test
    @DisplayName("boot fails when two manifests declare the same permission")
    void duplicatePermission() {
        LoadedModuleManifest a = TestModuleCatalogs.withPermissions("a", ModuleCategory.BUSINESS, true, List.of("X_VIEW"));
        LoadedModuleManifest b = TestModuleCatalogs.withPermissions("b", ModuleCategory.BUSINESS, true, List.of("X_VIEW"));
        ModuleCatalog catalog = new ModuleCatalog(List.of(TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true), a, b));
        assertThatThrownBy(permissions(catalog)::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("X_VIEW");
    }

    @Test
    @DisplayName("the shipped manifests are valid: every module declares the three roles, VIEWER holds reads only")
    void shippedManifests() {
        ModuleCatalog catalog = new ModuleCatalog(
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver());
        ModulePermissions permissions = permissions(catalog);
        permissions.afterSingletonsInstantiated();
        // 20.10 / 19.14 reprise (user decision 2026-11-11): expenses for the OWNER only — no EXPENSE_* for any
        // built-in member role (an owner-defined role may grant them)
        assertThat(permissions.permissionsFor(Role.EMPLOYEE))
                .containsExactlyInAnyOrder("INVOICE_VIEW", "INVOICE_CREATE", "INVOICE_SEND", "SALE_CREATE");
        assertThat(permissions.permissionsFor(Role.MANAGER))
                .containsExactlyInAnyOrder("INVOICE_VIEW", "INVOICE_CREATE", "INVOICE_SEND", "PURCHASE_PRICE_VIEW",
                        // 19.5b — fine rights of the store manager
                        "STORE_SALES_VIEW", "INVENTORY_SESSION_MANAGE", "PRODUCT_MERGE", "TEAM_MANAGE",
                        // 19.14 — selling and the store's documents, as permissions (same holders as before)
                        "SALE_CREATE", "DOCUMENT_STORE_VIEW");
        // 19.5b — a VIEWER reads his store's sales
        assertThat(permissions.permissionsFor(Role.VIEWER)).containsExactlyInAnyOrder("INVOICE_VIEW",
                "STORE_SALES_VIEW", "DOCUMENT_STORE_VIEW");
        assertThat(permissions.permissionsFor(Role.OWNER)).contains("EXPENSE_VIEW", "EXPENSE_CREATE",
                "EXPENSE_DELETE", "PURCHASE_PRICE_VIEW");
        // 16.13 reprise (user decision 2026-11-10): purchase prices for the OWNER and the MANAGER only
        assertThat(permissions.permissionsFor(Role.EMPLOYEE)).doesNotContain("PURCHASE_PRICE_VIEW");
    }
}
