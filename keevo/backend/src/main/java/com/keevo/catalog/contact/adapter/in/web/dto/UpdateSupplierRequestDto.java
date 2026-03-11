package com.keevo.catalog.contact.adapter.in.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for partially updating a supplier (PATCH — all fields nullable) (Story 2.5).
 */
public record UpdateSupplierRequestDto(
        String name,
        String phone,
        String email,
        List<UUID> productIds
) {}
