package com.keevo.crm.api;

import java.util.Optional;
import java.util.UUID;

/** Read access to the crm's suppliers for other modules. */
public interface SupplierQueryPort {

    boolean existsById(String tenantSchema, UUID supplierId);

    /** The supplier linked to a product (with all its product ids), empty when none is linked. */
    Optional<SupplierView> findByProductId(String tenantSchema, UUID productId);
}
