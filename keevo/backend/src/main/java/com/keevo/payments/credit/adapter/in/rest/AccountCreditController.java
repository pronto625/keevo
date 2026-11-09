package com.keevo.payments.credit.adapter.in.rest;

import com.keevo.payments.credit.application.service.AccountCreditService;
import com.keevo.payments.credit.application.service.AccountCreditService.CreditPage;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 21.3 — the owner's account credit (« Mon plan »): {@code GET /api/v1/payments/credit}. ZERO business logic.
 * OWNER of the company only (store members and the super-admin: 403).
 */
@Tag(name = "Payments", description = "Self-service plan payment (Mobile Money)")
@EntitlementExempt("core: abonnement")
@RestController
@RequestMapping("/api/v1/payments")
public class AccountCreditController {

    private final AccountCreditService credits;

    public AccountCreditController(AccountCreditService credits) {
        this.credits = credits;
    }

    @Operation(summary = "Account credit of the company: balance and history (newest first)", description = """
            balanceXaf is usable on a payment (checkout with useCredit=true: applied first, the provider is asked for
            the rest; a price fully covered is confirmed at once). movements: kind (app logic), label and detail
            (French), amountXaf signed, balanceAfterXaf, at. limit 1..100 (default 20); cursor = nextCursor of the
            previous page.
            """, security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/credit")
    public ResponseEntity<ApiResponseWrapper<CreditPage>> credit(@RequestParam(required = false) String cursor,
                                                                 @RequestParam(required = false) Integer limit) {
        CurrentActor.requireOwner("Seul le propriétaire peut consulter le crédit du compte");
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                credits.ownerPage(TenantContext.getCurrentTenant(), cursor, limit)));
    }
}
