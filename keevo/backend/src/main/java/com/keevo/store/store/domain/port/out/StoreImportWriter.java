package com.keevo.store.store.domain.port.out;

import com.keevo.store.api.StoreImportPort.ImportedStore;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.2 — batch insert of imported stores (Bref migration), {@code ON CONFLICT (id) DO NOTHING}, with their
 * original dates. Values arrive normalized.
 */
public interface StoreImportWriter {

    /** @return the ids inserted */
    Set<UUID> insertAbsent(String tenantSchema, List<ImportedStore> stores);
}
