package com.keevo.crm.contact.adapter.in.web.dto;

import jakarta.validation.constraints.NotNull;

/** Story 20.2 — body of {@code PUT /api/v1/clients/{id}/marketing-consent}. */
public record MarketingConsentRequestDto(
        @NotNull(message = "Indiquez si le client accepte les offres (true ou false)")
        Boolean consent
) {}
