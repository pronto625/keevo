package com.keevo.catalog.contact.adapter.in.web.dto;

/**
 * Request DTO for partially updating a client (PATCH — all fields nullable) (Story 2.5).
 */
public record UpdateClientRequestDto(
        String name,
        String phone,
        String email,
        String notes
) {}
