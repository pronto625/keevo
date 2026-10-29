package com.keevo.crm.api;

import java.util.UUID;

/**
 * Write access to the crm's suppliers for other modules. Validation, audit events and
 * transactions are those of the crm.
 */
public interface SupplierCommandPort {

    /**
     * Persists a supplier with a caller-assigned id and links its products, without the audit
     * event of the REST create (offline-first sync create).
     *
     * @return the created supplier; its {@code productIds} are the requested links
     * @throws com.keevo.shared.domain.exception.DomainException VALIDATION_ERROR on invalid fields
     */
    SupplierView createWithId(String tenantSchema, CreateSupplierInput input);

    /** @return the updated supplier; {@code productIds} echoes the input (empty when the links were kept) */
    SupplierView update(String tenantSchema, UpdateSupplierInput input);

    void archive(String tenantSchema, UUID supplierId, UUID actorId);

    void unarchive(String tenantSchema, UUID supplierId, UUID actorId);
}
