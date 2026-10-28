package com.keevo.architecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Classifier used by {@link ApplicationModulesBaselineTest} on sample Spring Modulith 1.4 messages. */
class ModulithViolationClassifierTest {

    private static final String MODULE_DECLARED =
        "Module 'inventory' depends on module 'catalog' via com.keevo.inventory.A -> com.keevo.catalog.B. "
            + "Allowed targets: catalog, shared, store, subscription.";
    private static final String MODULE_UNDECLARED =
        "Module 'inventory' depends on module 'commerce' via com.keevo.inventory.A -> com.keevo.commerce.B. "
            + "Allowed targets: catalog, shared, store, subscription.";
    private static final String NAMED_INTERFACE_DECLARED =
        "Module 'inventory' depends on named interface(s) 'catalog :: api' via com.keevo.inventory.A -> "
            + "com.keevo.catalog.api.B. Allowed targets: catalog :: api, shared.";
    private static final String NAMED_INTERFACE_UNDECLARED =
        "Module 'inventory' depends on named interface(s) 'crm :: api' via com.keevo.inventory.A -> "
            + "com.keevo.crm.api.B. Allowed targets: catalog :: api, shared.";

    @Test
    void moduleFormDeclaredTargetIsNotNotAllowed() {
        assertThat(ApplicationModulesBaselineTest.isModuleDependency(MODULE_DECLARED, true)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isModuleDependency(MODULE_DECLARED, false)).isFalse();
    }

    @Test
    void moduleFormUndeclaredTargetIsNotAllowed() {
        assertThat(ApplicationModulesBaselineTest.isModuleDependency(MODULE_UNDECLARED, false)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isModuleDependency(MODULE_UNDECLARED, true)).isFalse();
    }

    @Test
    void namedInterfaceFormDeclaredTargetIsNotNotAllowed() {
        assertThat(ApplicationModulesBaselineTest.isModuleDependency(NAMED_INTERFACE_DECLARED, true)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isModuleDependency(NAMED_INTERFACE_DECLARED, false)).isFalse();
    }

    @Test
    void namedInterfaceFormUndeclaredTargetIsNotAllowed() {
        assertThat(ApplicationModulesBaselineTest.isModuleDependency(NAMED_INTERFACE_UNDECLARED, false)).isTrue();
    }

    @Test
    void moduleOfStripsTheNamedInterfaceSuffix() {
        assertThat(ApplicationModulesBaselineTest.moduleOf("catalog :: api")).isEqualTo("catalog");
        assertThat(ApplicationModulesBaselineTest.moduleOf("catalog :: api, catalog :: spi")).isEqualTo("catalog");
        assertThat(ApplicationModulesBaselineTest.moduleOf("shared")).isEqualTo("shared");
    }
}
