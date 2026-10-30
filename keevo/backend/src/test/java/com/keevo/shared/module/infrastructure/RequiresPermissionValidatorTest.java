package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.RequiresPermission;
import com.keevo.shared.module.TestModuleCatalogs;
import com.keevo.shared.module.application.KeevoModuleDirectory;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.ModulePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("@RequiresPermission — startup validation (Story 14.1)")
class RequiresPermissionValidatorTest {

    static class Declared {
        @RequiresPermission("INVOICE_VIEW")
        public void view() {
        }
    }

    @RequiresPermission("GHOST_PERMISSION")
    static class Undeclared {
        public void anything() {
        }
    }

    private static RequiresPermissionValidator validator() {
        ModuleCatalog catalog = new ModuleCatalog(List.of(TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true),
                TestModuleCatalogs.withPermissions("invoicing", ModuleCategory.PREMIUM, false, List.of("INVOICE_VIEW"))));
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("permissions", new ModulePermissions(catalog, KeevoModuleDirectory.empty(catalog)));
        return new RequiresPermissionValidator(factory.getBeanProvider(ModulePermissions.class));
    }

    @Test
    @DisplayName("a declared permission passes; an undeclared one fails the startup, naming the class")
    void values() {
        RequiresPermissionValidator ok = validator();
        ok.postProcessBeforeInitialization(new Declared(), "declared");
        assertThatCode(ok::afterSingletonsInstantiated).doesNotThrowAnyException();

        RequiresPermissionValidator bad = validator();
        bad.postProcessBeforeInitialization(new Undeclared(), "undeclared");
        assertThatThrownBy(bad::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Undeclared").hasMessageContaining("GHOST_PERMISSION");

        RequiresPermissionValidator late = validator();
        late.afterSingletonsInstantiated();
        assertThatThrownBy(() -> late.postProcessBeforeInitialization(new Undeclared(), "undeclared"))
                .isInstanceOf(IllegalStateException.class);
    }
}
