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

    private static final String NON_EXPOSED_INTO_INVENTORY =
        "Module 'commerce' depends on non-exposed type com.keevo.inventory.stock.domain.entity.StockLevel "
            + "within module 'inventory'!";
    private static final String NON_EXPOSED_FROM_SHARED =
        "Module 'shared' depends on non-exposed type com.keevo.inventory.stock.domain.entity.StockLevel "
            + "within module 'inventory'!";
    private static final String NON_EXPOSED_INTO_STORE =
        "Module 'commerce' depends on non-exposed type com.keevo.store.store.domain.model.Store "
            + "within module 'store'!";
    /** sync is a consumer only and publishes no API (12.2e): its internals are not pinned. */
    private static final String NON_EXPOSED_INTO_REPORTING =
        "Module 'identity' depends on non-exposed type com.keevo.reporting.report.application.service.DailyReportFormatter "
            + "within module 'reporting'!";
    private static final String INTERNAL_OF_DECLARED_FEEDBACK =
        "Module 'admin' depends on module 'feedback' via com.keevo.admin.A -> com.keevo.feedback.feedback.domain.model.Feedback. "
            + "Allowed targets: feedback :: api, shared.";
    private static final String NON_EXPOSED_INTO_SYNC =
        "Module 'commerce' depends on non-exposed type com.keevo.sync.sync.domain.model.SyncOperation "
            + "within module 'sync'!";
    private static final String NON_EXPOSED_INTO_MESSAGING =
        "Module 'commerce' depends on non-exposed type com.keevo.messaging.notification.domain.model.DeviceToken "
            + "within module 'messaging'!";
    private static final String INTERNAL_OF_DECLARED_CATALOG =
        "Module 'sync' depends on module 'catalog' via com.keevo.sync.A -> com.keevo.catalog.product.domain.entity.Product. "
            + "Allowed targets: catalog :: api, shared.";

    @Test
    void internalAccessToPublishedModuleIsFlaggedExceptFromShared() {
        assertThat(ApplicationModulesBaselineTest.isInternalAccessToPublishedModule(NON_EXPOSED_INTO_INVENTORY)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isInternalAccessToPublishedModule(INTERNAL_OF_DECLARED_CATALOG)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isInternalAccessToPublishedModule(NON_EXPOSED_FROM_SHARED)).isFalse();
        // store's API is published since 12.2d
        assertThat(ApplicationModulesBaselineTest.isInternalAccessToPublishedModule(NON_EXPOSED_INTO_STORE)).isTrue();
        // messaging, reporting and feedback are published since 12.2e
        assertThat(ApplicationModulesBaselineTest.isInternalAccessToPublishedModule(NON_EXPOSED_INTO_MESSAGING)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isInternalAccessToPublishedModule(NON_EXPOSED_INTO_REPORTING)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isInternalAccessToPublishedModule(INTERNAL_OF_DECLARED_FEEDBACK)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isInternalAccessToPublishedModule(NON_EXPOSED_INTO_SYNC)).isFalse();
        assertThat(ApplicationModulesBaselineTest.isInternalAccessToPublishedModule(MODULE_UNDECLARED)).isFalse();
    }

    /** Real Modulith 1.4 messages, captured 2026-10-29 with a temporary probe class in shared (not committed). */
    private static final String SHARED_ALLOWED = "Allowed targets: catalog :: events, commerce :: api, "
        + "commerce :: events, crm :: events, identity :: api, identity :: events, inventory :: events, "
        + "reporting :: events, store :: api, store :: events.";
    private static final String SHARED_INTO_IDENTITY_INTERNAL =
        "Module 'shared' depends on module 'identity' via com.keevo.shared.tmpprobe.TmpProbe -> "
            + "com.keevo.identity.auth.domain.port.out.UserRepository. " + SHARED_ALLOWED;
    private static final String SHARED_INTO_INVENTORY_INTERNAL =
        "Module 'shared' depends on module 'inventory' via com.keevo.shared.tmpprobe.TmpProbe -> "
            + "com.keevo.inventory.stock.domain.entity.StockLevel. " + SHARED_ALLOWED;
    /** admin is not in shared's allow-list: Modulith reports it as a module dependency on an undeclared target. */
    private static final String SHARED_INTO_ADMIN =
        "Module 'shared' depends on module 'admin' via com.keevo.shared.tmpprobe.TmpProbe -> "
            + "com.keevo.admin.alerts.domain.port.in.PurgeAlertAcksUseCase. " + SHARED_ALLOWED;

    @Test
    void internalAccessFromSharedIsFlaggedWhateverTheTarget() {
        // pre-12.2f form (shared had no allow-list)
        assertThat(ApplicationModulesBaselineTest.isInternalAccessFromShared(NON_EXPOSED_FROM_SHARED)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isInternalAccessFromShared(SHARED_INTO_IDENTITY_INTERNAL)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isInternalAccessFromShared(SHARED_INTO_INVENTORY_INTERNAL)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isInternalAccessFromShared(NON_EXPOSED_INTO_INVENTORY)).isFalse();
        assertThat(ApplicationModulesBaselineTest.isInternalAccessFromShared(INTERNAL_OF_DECLARED_CATALOG)).isFalse();
    }

    @Test
    void sharedIntoAModuleOutsideItsAllowListIsNotAllowed() {
        assertThat(ApplicationModulesBaselineTest.isModuleDependency(SHARED_INTO_ADMIN, false)).isTrue();
        assertThat(ApplicationModulesBaselineTest.isInternalAccessFromShared(SHARED_INTO_ADMIN)).isFalse();
    }
}
