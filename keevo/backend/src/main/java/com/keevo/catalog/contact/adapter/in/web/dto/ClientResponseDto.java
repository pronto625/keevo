package com.keevo.catalog.contact.adapter.in.web.dto;

import com.keevo.catalog.contact.domain.entity.Client;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for Client API endpoints (Story 2.5).
 */
public record ClientResponseDto(
        UUID id,
        String name,
        String phone,
        String email,
        String notes,
        boolean archived,
        Instant createdAt,
        Instant updatedAt
) {
    public static ClientResponseDto fromDomain(Client client) {
        return new ClientResponseDto(
                client.id(),
                client.name(),
                client.phone(),
                client.email(),
                client.notes(),
                client.archived(),
                client.createdAt(),
                client.updatedAt()
        );
    }
}
