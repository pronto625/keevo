package com.keevo.admin.subscription.adapter.in.rest;

import com.keevo.admin.subscription.adapter.in.rest.dto.ActivatePremiumRequest;
import com.keevo.admin.subscription.adapter.in.rest.dto.DowngradeToFreeRequest;
import com.keevo.admin.subscription.adapter.in.rest.dto.PlanAlertDto;
import com.keevo.admin.subscription.adapter.in.rest.dto.PlanStateDto;
import com.keevo.admin.subscription.adapter.in.rest.dto.SuspendTenantRequest;
import com.keevo.admin.subscription.adapter.in.rest.dto.TenantPaymentDto;
import com.keevo.admin.subscription.domain.model.PaymentMethod;
import com.keevo.admin.subscription.domain.port.in.ActivatePremiumCommand;
import com.keevo.admin.subscription.domain.port.in.ActivatePremiumUseCase;
import com.keevo.admin.subscription.domain.port.in.DowngradeToFreeCommand;
import com.keevo.admin.subscription.domain.port.in.DowngradeToFreeUseCase;
import com.keevo.admin.subscription.domain.port.in.ListPlanAlertsUseCase;
import com.keevo.admin.subscription.domain.port.in.ListTenantPaymentsUseCase;
import com.keevo.admin.subscription.domain.port.in.ReactivateTenantUseCase;
import com.keevo.admin.subscription.domain.port.in.SuspendTenantCommand;
import com.keevo.admin.subscription.domain.port.in.SuspendTenantUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.subscription.plan.domain.port.in.CurrentPlan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AdminTenantPlanController — super-admin plan & payment management of a tenant (Story 9-2).
 *
 * <p>ZERO business logic: request parsing to commands, {@link CurrentActor#requireSuperAdmin()} in
 * every method (second barrier behind the SecurityConfig {@code /api/v1/admin/**} rule).
 * The legacy {@code POST /api/v1/admin/subscriptions/{schemaName}/activate} stays in
 * {@code AdminSubscriptionController}.
 */
@Tag(name = "Admin — Plans", description = "Super Admin plan activation, payments, suspension")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminTenantPlanController {

    private final ActivatePremiumUseCase activatePremiumUseCase;
    private final DowngradeToFreeUseCase downgradeToFreeUseCase;
    private final SuspendTenantUseCase suspendTenantUseCase;
    private final ReactivateTenantUseCase reactivateTenantUseCase;
    private final ListTenantPaymentsUseCase listTenantPaymentsUseCase;
    private final ListPlanAlertsUseCase listPlanAlertsUseCase;

    public AdminTenantPlanController(ActivatePremiumUseCase activatePremiumUseCase,
                                     DowngradeToFreeUseCase downgradeToFreeUseCase,
                                     SuspendTenantUseCase suspendTenantUseCase,
                                     ReactivateTenantUseCase reactivateTenantUseCase,
                                     ListTenantPaymentsUseCase listTenantPaymentsUseCase,
                                     ListPlanAlertsUseCase listPlanAlertsUseCase) {
        this.activatePremiumUseCase = activatePremiumUseCase;
        this.downgradeToFreeUseCase = downgradeToFreeUseCase;
        this.suspendTenantUseCase = suspendTenantUseCase;
        this.reactivateTenantUseCase = reactivateTenantUseCase;
        this.listTenantPaymentsUseCase = listTenantPaymentsUseCase;
        this.listPlanAlertsUseCase = listPlanAlertsUseCase;
    }

    @Operation(summary = "Activate / renew Premium after an off-platform payment",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tenants/{tenantId}/plan/premium")
    public ResponseEntity<ApiResponseWrapper<PlanStateDto>> activatePremium(
            @PathVariable UUID tenantId,
            @Valid @RequestBody ActivatePremiumRequest request) {
        CurrentActor.requireSuperAdmin();
        CurrentPlan plan = activatePremiumUseCase.activatePremium(new ActivatePremiumCommand(
                CurrentActor.actorId(), tenantId,
                parseDate("expiresAt", request.expiresAt()),
                request.amountXaf(),
                PaymentMethod.valueOf(request.method()),
                parsePaidAt(request.paidAt()),
                request.note()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                new PlanStateDto(plan.planType().name(), plan.expiresAt())));
    }

    @Operation(summary = "Move a tenant back to the FREE plan", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tenants/{tenantId}/plan/free")
    public ResponseEntity<ApiResponseWrapper<PlanStateDto>> downgradeToFree(
            @PathVariable UUID tenantId,
            @Valid @RequestBody(required = false) DowngradeToFreeRequest request) {
        CurrentActor.requireSuperAdmin();
        downgradeToFreeUseCase.downgradeToFree(new DowngradeToFreeCommand(
                CurrentActor.actorId(), tenantId, request != null ? request.reason() : null));
        return ResponseEntity.ok(ApiResponseWrapper.ok(new PlanStateDto("FREE", null)));
    }

    @Operation(summary = "Suspend a tenant (read-only)", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tenants/{tenantId}/suspend")
    public ResponseEntity<ApiResponseWrapper<Void>> suspend(
            @PathVariable UUID tenantId,
            @Valid @RequestBody(required = false) SuspendTenantRequest request) {
        CurrentActor.requireSuperAdmin();
        if (request == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Motif requis",
                    Map.of("reason", "Motif requis"));
        }
        suspendTenantUseCase.suspend(new SuspendTenantCommand(CurrentActor.actorId(), tenantId, request.reason()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "Lift a tenant suspension", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tenants/{tenantId}/reactivate")
    public ResponseEntity<ApiResponseWrapper<Void>> reactivate(@PathVariable UUID tenantId) {
        CurrentActor.requireSuperAdmin();
        reactivateTenantUseCase.reactivate(CurrentActor.actorId(), tenantId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "Payment history of a tenant (newest first)", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tenants/{tenantId}/payments")
    public ResponseEntity<ApiResponseWrapper<List<TenantPaymentDto>>> payments(@PathVariable UUID tenantId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                listTenantPaymentsUseCase.listPayments(tenantId).stream().map(TenantPaymentDto::from).toList()));
    }

    @Operation(summary = "Plans expiring within 7 days and plans expired in the last 7 days",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/subscriptions/alerts")
    public ResponseEntity<ApiResponseWrapper<List<PlanAlertDto>>> alerts() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                listPlanAlertsUseCase.listAlerts().stream().map(PlanAlertDto::from).toList()));
    }

    // ── Parsing (adapter concern) ─────────────────────────────────────────────

    private static LocalDate parseDate(String field, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw invalid(field, "Date invalide");
        }
    }

    /** ISO date-time with offset, ISO instant, or plain date (start of that day, WAT: never in the future for today); blank → null (now). */
    private static Instant parsePaidAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // try a plain date
        }
        try {
            return LocalDate.parse(value)
                    .atStartOfDay(com.keevo.admin.subscription.application.service.AdminPlanService.WAT).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid("paidAt", "Date de paiement invalide");
        }
    }

    private static DomainException invalid(String field, String message) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message, Map.of(field, message));
    }
}
