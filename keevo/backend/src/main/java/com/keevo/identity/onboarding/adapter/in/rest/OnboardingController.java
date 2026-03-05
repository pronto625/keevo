package com.keevo.identity.onboarding.adapter.in.rest;

import com.keevo.identity.onboarding.adapter.in.rest.dto.OnboardingRequest;
import com.keevo.identity.onboarding.adapter.in.rest.dto.OnboardingResponse;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.port.in.CompleteOnboardingCommand;
import com.keevo.identity.onboarding.domain.port.in.CompleteOnboardingUseCase;
import com.keevo.identity.onboarding.domain.port.in.OnboardingResult;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * OnboardingController — REST adapter for the onboarding completion endpoint.
 *
 * <p>Architecture rules:
 * - ZERO business logic in this class
 * - Maps DTO → Command → delegates to use case → maps result → DTO
 * - actorId extracted from SecurityContext principal (set by JwtAuthFilter)
 * - MCP Port Purity: actorId passed explicitly to command, never read from HttpServletRequest
 *
 * <p>Protected endpoint: falls under /api/v1/** in SecurityConfig — JWT required.
 */
@Tag(name = "Onboarding", description = "Onboarding wizard completion endpoint")
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final CompleteOnboardingUseCase completeOnboardingUseCase;

    public OnboardingController(CompleteOnboardingUseCase completeOnboardingUseCase) {
        this.completeOnboardingUseCase = completeOnboardingUseCase;
    }

    @Operation(
        summary = "Complete onboarding wizard",
        description = """
            Completes the onboarding wizard by setting the business sector and shop name.

            - Creates default categories from the sector template (Strategy pattern)
            - Updates the store name from the default "Ma Boutique"
            - Seeds tenant preferences with defaults (EOD report time, stock alerts)
            - Publishes OnboardingCompletedEvent for audit trail

            JWT authentication required.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Onboarding completed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid sector type"),
        @ApiResponse(responseCode = "401", description = "JWT missing or expired"),
        @ApiResponse(responseCode = "422", description = "Validation error (blank name, missing sector)")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/complete")
    public ResponseEntity<OnboardingResponse> complete(
            @Valid @RequestBody OnboardingRequest request) {

        // Extract actorId from SecurityContext — set by JwtAuthFilter (principal = userId UUID)
        UUID actorId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        // Parse sectorType — throws DomainException(SECTOR_TEMPLATE_NOT_FOUND) if invalid
        SectorType sectorType = parseSectorType(request.sectorType());

        CompleteOnboardingCommand command = new CompleteOnboardingCommand(
            sectorType,
            request.storeName(),
            actorId
        );

        OnboardingResult result = completeOnboardingUseCase.complete(command);

        return ResponseEntity.ok(new OnboardingResponse(
            result.tenantId(),
            result.sectorType().name(),
            result.storeName(),
            result.categoriesCreated()
        ));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Parse sectorType string to SectorType enum.
     * Throws DomainException(SECTOR_TEMPLATE_NOT_FOUND) for invalid sectors.
     */
    private SectorType parseSectorType(String sectorTypeStr) {
        try {
            return SectorType.valueOf(sectorTypeStr);
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.SECTOR_TEMPLATE_NOT_FOUND,
                "Unknown sector type: " + sectorTypeStr);
        }
    }
}
