package com.keevo.shared.module.application;

import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.TestModuleCatalogs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModulePermissions — role mapping of the module permissions (Story 14.1)")
class ModulePermissionsTest {

    private static ModuleCatalog catalog() {
        return new ModuleCatalog(List.of(TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true),
                TestModuleCatalogs.withPermissions("invoicing", ModuleCategory.PREMIUM, false,
                        List.of("INVOICE_CREATE", "INVOICE_VIEW", "INVOICE_MANAGE")),
                TestModuleCatalogs.withPermissions("crm", ModuleCategory.BUSINESS, true, List.of("CLIENT_EXPORT"))));
    }

    private static KeevoModule module(String id, Set<String> employee) {
        return new KeevoModule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> employeePermissions() {
                return employee;
            }
        };
    }

    @Test
    @DisplayName("OWNER: every declared permission; EMPLOYEE: what the modules grant; SUPER_ADMIN / none: nothing")
    void mapping() {
        ModuleCatalog catalog = catalog();
        ModulePermissions permissions = new ModulePermissions(catalog, KeevoModuleDirectory.of(catalog,
                List.of(module("invoicing", Set.of("INVOICE_VIEW", "INVOICE_CREATE")))));
        assertThat(permissions.permissionsOf(List.of("OWNER")))
                .containsExactlyInAnyOrder("INVOICE_CREATE", "INVOICE_VIEW", "INVOICE_MANAGE", "CLIENT_EXPORT");
        assertThat(permissions.permissionsOf(List.of("EMPLOYEE"))).containsExactlyInAnyOrder("INVOICE_VIEW", "INVOICE_CREATE");
        assertThat(permissions.permissionsOf(List.of("SUPER_ADMIN"))).isEmpty();
        assertThat(permissions.permissionsOf(List.of())).isEmpty();
        assertThat(permissions.isDeclared("INVOICE_MANAGE")).isTrue();
        assertThat(permissions.isDeclared("GHOST")).isFalse();
    }

    @Test
    @DisplayName("boot fails when a module grants EMPLOYEE a permission its manifest does not declare")
    void undeclaredEmployeePermission() {
        ModuleCatalog catalog = catalog();
        ModulePermissions permissions = new ModulePermissions(catalog, KeevoModuleDirectory.of(catalog,
                List.of(module("invoicing", Set.of("CLIENT_EXPORT")))));
        assertThatThrownBy(permissions::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invoicing").hasMessageContaining("CLIENT_EXPORT");
    }

    @Test
    @DisplayName("boot fails when two manifests declare the same permission")
    void duplicatePermission() {
        ModuleCatalog catalog = new ModuleCatalog(List.of(TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true),
                TestModuleCatalogs.withPermissions("a", ModuleCategory.BUSINESS, true, List.of("X_VIEW")),
                TestModuleCatalogs.withPermissions("b", ModuleCategory.BUSINESS, true, List.of("X_VIEW"))));
        ModulePermissions permissions = new ModulePermissions(catalog, KeevoModuleDirectory.empty(catalog));
        assertThatThrownBy(permissions::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("X_VIEW");
    }
}
