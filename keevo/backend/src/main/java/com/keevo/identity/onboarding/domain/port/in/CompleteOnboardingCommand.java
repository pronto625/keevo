package com.keevo.identity.onboarding.domain.port.in;

import com.keevo.identity.onboarding.domain.model.SectorType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * CompleteOnboardingCommand — Input data for the onboarding completion use case.
 *
 * <p>MCP Port Purity: contains ONLY plain Java types — no HttpServletRequest or Principal.
 * The actorId is extracted in the REST adapter and passed explicitly.
 *
 * <p>Pure Java record — immutable, validated at REST layer.
 */
public record CompleteOnboardingCommand(
        @NotNull SectorType sectorType,
        @NotBlank @Size(min = 2, max = 100) String storeName,
        UUID actorId
) {}
