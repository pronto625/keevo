package com.keevo.admin.alerts.adapter.in.rest;

import com.keevo.admin.alerts.adapter.in.rest.dto.AdminAlertDto;
import com.keevo.admin.alerts.domain.model.AlertSeverity;
import com.keevo.admin.alerts.domain.port.in.CountAdminAlertsUseCase;
import com.keevo.admin.alerts.domain.port.in.HandleAdminAlertUseCase;
import com.keevo.admin.alerts.domain.port.in.ListAdminAlertsUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AdminAlertController — « Alertes » feed of the super admin (Story 9-5).
 *
 * <p>Inherits the {@code /api/v1/admin/**} {@code SUPER_ADMIN} filter from SecurityConfig, plus the
 * class-level {@code @PreAuthorize} and an explicit {@link CurrentActor#requireSuperAdmin()} per method.
 */
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/alerts")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminAlertController {

    private final ListAdminAlertsUseCase listAlerts;
    private final HandleAdminAlertUseCase handleAlert;
    private final CountAdminAlertsUseCase countAlerts;

    public AdminAlertController(ListAdminAlertsUseCase listAlerts, HandleAdminAlertUseCase handleAlert,
                                CountAdminAlertsUseCase countAlerts) {
        this.listAlerts = listAlerts;
        this.handleAlert = handleAlert;
        this.countAlerts = countAlerts;
    }

    /** @param includeHandled {@code false} (default) hides handled alerts; any other value than true/false → 422 */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<AdminAlertDto>>> list(
            @RequestParam(defaultValue = "false") String includeHandled) {
        CurrentActor.requireSuperAdmin();
        List<AdminAlertDto> alerts = listAlerts.listAlerts(parseBoolean(includeHandled)).stream()
                .map(AdminAlertDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(alerts));
    }

    /**
     * Unhandled alerts count of the sidebar badge (served from the 30 s cached feed).
     *
     * @param severity HIGH | MEDIUM | LOW (case-insensitive), absent = all; other values → 422
     */
    @GetMapping("/count")
    public ResponseEntity<ApiResponseWrapper<Long>> count(@RequestParam(required = false) String severity) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(countAlerts.countUnhandled(parseSeverity(severity))));
    }

    /** Marks the alert handled (idempotent; an unknown but well-formed key is accepted) → 204. */
    @PostMapping("/{key}/handle")
    public ResponseEntity<Void> handle(@PathVariable String key) {
        CurrentActor.requireSuperAdmin();
        handleAlert.markHandled(key, CurrentActor.actorId());
        return ResponseEntity.noContent().build();
    }

    private static AlertSeverity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return AlertSeverity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "severity must be HIGH, MEDIUM or LOW",
                    Map.of("severity", "Valeur attendue : HIGH, MEDIUM ou LOW"));
        }
    }

    private static boolean parseBoolean(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "", "false" -> false;
            case "true" -> true;
            default -> throw new DomainException(ErrorCode.VALIDATION_ERROR, "includeHandled must be true or false",
                    Map.of("includeHandled", "Valeur attendue : true ou false"));
        };
    }
}
