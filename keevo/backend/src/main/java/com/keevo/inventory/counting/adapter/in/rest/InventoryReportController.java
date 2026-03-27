package com.keevo.inventory.counting.adapter.in.rest;

import com.keevo.inventory.counting.adapter.in.rest.dto.InventoryGapReportResponseDto;
import com.keevo.inventory.counting.domain.event.InventoryReportGeneratedEvent;
import com.keevo.inventory.counting.domain.model.InventoryGapReport;
import com.keevo.inventory.counting.domain.port.in.GenerateGapReportQuery;
import com.keevo.inventory.counting.domain.port.in.GenerateGapReportUseCase;
import com.keevo.inventory.counting.domain.service.InventoryReportTextFormatter;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * REST controller for inventory gap report generation and export.
 * Story 6.3 — Gap Analysis Report.
 */
@RestController
@RequestMapping("/api/v1/inventory/sessions/{sessionId}")
@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
@Tag(name = "Inventory Report", description = "Inventory gap report generation and export")
public class InventoryReportController {

    private final GenerateGapReportUseCase generateGapReportUseCase;
    private final InventoryReportTextFormatter textFormatter;
    private final ApplicationEventPublisher eventPublisher;

    public InventoryReportController(GenerateGapReportUseCase generateGapReportUseCase,
                                      InventoryReportTextFormatter textFormatter,
                                      ApplicationEventPublisher eventPublisher) {
        this.generateGapReportUseCase = generateGapReportUseCase;
        this.textFormatter = textFormatter;
        this.eventPublisher = eventPublisher;
    }

    @Operation(summary = "Generate gap analysis report for an inventory session")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Gap report generated"),
            @ApiResponse(responseCode = "404", description = "Session not found")
    })
    @GetMapping("/gap-report")
    public ResponseEntity<ApiResponseWrapper<InventoryGapReportResponseDto>> getGapReport(
            @PathVariable UUID sessionId) {
        UUID actorId = extractActorId();
        InventoryGapReport report = generateGapReportUseCase.execute(
                new GenerateGapReportQuery(sessionId, actorId));

        publishAuditEvent(sessionId, actorId);

        return ResponseEntity.ok(ApiResponseWrapper.ok(
                InventoryGapReportResponseDto.fromDomain(report)));
    }

    @Operation(summary = "Get WhatsApp-formatted text report")
    @GetMapping("/gap-report/text")
    public ResponseEntity<ApiResponseWrapper<String>> getGapReportText(
            @PathVariable UUID sessionId) {
        UUID actorId = extractActorId();
        InventoryGapReport report = generateGapReportUseCase.execute(
                new GenerateGapReportQuery(sessionId, actorId));
        String text = textFormatter.formatWhatsApp(report, "Utilisateur");
        return ResponseEntity.ok(ApiResponseWrapper.ok(text));
    }

    private UUID extractActorId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(auth.getName());
    }

    private void publishAuditEvent(UUID sessionId, UUID actorId) {
        String tenantId = TenantContext.getCurrentTenant();
        eventPublisher.publishEvent(new InventoryReportGeneratedEvent(
                sessionId, actorId, tenantId, Instant.now()));
    }
}
