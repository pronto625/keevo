package com.keevo.payments.credit.adapter.in.rest;

import com.keevo.payments.credit.application.service.AccountCreditService;
import com.keevo.payments.credit.application.service.AccountCreditService.AdminCreditPage;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Story 21.3 — the account credit of a company for the support (super-admin): balance, history with who and why, and
 * adjustments. ZERO business logic. {@link CurrentActor#requireSuperAdmin()} in every method (second barrier behind
 * the SecurityConfig {@code /api/v1/admin/**} rule).
 */
@Tag(name = "Admin — Account credit", description = "Super Admin account credit of a company")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/credit")
public class AdminAccountCreditController {

    /** @param amountXaf signed: + adds, − removes; @param reason why (3..300 characters, kept in the history) */
    public record AdjustmentRequest(Long amountXaf, String reason) {
    }

    private final AccountCreditService credits;

    public AdminAccountCreditController(AccountCreditService credits) {
        this.credits = credits;
    }

    @Operation(summary = "Account credit of a company: balance and history (who, why), newest first; limit 1..100, "
            + "cursor = nextCursor", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<AdminCreditPage>> credit(@PathVariable UUID tenantId,
                                                                      @RequestParam(required = false) String cursor,
                                                                      @RequestParam(required = false) Integer limit) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(credits.adminPage(tenantId, cursor, limit)));
    }

    @Operation(summary = "Add (amountXaf > 0) or remove (< 0) account credit with a reason; 409 when the removal "
            + "exceeds the balance (never negative)", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/adjustments")
    public ResponseEntity<ApiResponseWrapper<AdminCreditPage>> adjust(@PathVariable UUID tenantId,
                                                                      @RequestBody(required = false)
                                                                      AdjustmentRequest body) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(credits.adjust(tenantId, body == null ? null : body.amountXaf(),
                body == null ? null : body.reason(), CurrentActor.actorId())));
    }
}
