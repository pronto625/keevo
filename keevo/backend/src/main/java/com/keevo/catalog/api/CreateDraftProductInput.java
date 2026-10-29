package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Input of {@link ProductCommandPort#createDraft}.
 *
 * @param id client-generated id — {@code null} lets the server generate one
 */
public record CreateDraftProductInput(
        UUID id,
        String name,
        String description,
        UUID categoryId,
        Integer price,
        Integer buyPrice,
        Integer transportCost,
        UUID actorId,
        String actorRole,
        String actorName
) {}
