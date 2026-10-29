package com.keevo.crm.api;

import java.util.List;
import java.util.UUID;

/**
 * Input of {@link SupplierCommandPort#createWithId}: a supplier whose id was assigned on-device.
 *
 * @param productIds products to link — {@code null} or empty links none
 */
public record CreateSupplierInput(
        UUID id,
        String name,
        String phone,
        String email,
        List<UUID> productIds
) {}
