package com.keevo.crm.api;

import java.util.List;
import java.util.UUID;

/**
 * Input of {@link SupplierCommandPort#update}.
 *
 * @param productIds new product links — {@code null} keeps the current links
 */
public record UpdateSupplierInput(
        UUID supplierId,
        String name,
        String phone,
        String email,
        List<UUID> productIds,
        UUID actorId
) {}
