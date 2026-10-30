package com.keevo.shared.application.port.sync;


import java.util.Set;
import java.util.UUID;

/**
 * SyncOperationHandler — Strategy interface for processing sync operations by type.
 *
 * <p>Story 14.3: a public SPI of {@code shared} (like {@code ExportDatasetProvider}) so that a module can own the
 * handlers of its own operations (e.g. {@code crm} for clients and suppliers) without depending on {@code sync};
 * {@code sync} discovers every {@code @Component} implementation through {@code SyncOperationHandlerRegistry}.
 */
public interface SyncOperationHandler {

    /** Operation types this handler supports (e.g. "CREATE_SALE"). */
    Set<String> supportedTypes();

    /**
     * Id of the module that owns these operations (Story 12.7, MOD-NFR9) — an installed module id of the
     * catalogue ({@code keevo/modules/<id>/module.json}), e.g. {@code "pos"}, {@code "crm"}, {@code "core"}.
     * Checked at boot by {@code SyncOperationHandlerRegistry}; the push rejects the operations of a module that is
     * not active for the tenant with {@code MODULE_INACTIVE}.
     */
    String moduleId();

    /** Process one operation. Returns APPLIED, CONFLICT, or REJECTED. */
    SyncOperationResult handle(SyncOperation operation, UUID actorId, String tenantId);
}
