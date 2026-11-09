package com.keevo.payments.checkout.adapter.in.rest;

import com.keevo.payments.checkout.adapter.in.rest.dto.AuthorizeRequest;
import com.keevo.payments.checkout.adapter.in.rest.dto.CheckoutRequest;
import com.keevo.payments.checkout.application.service.CheckoutService;
import com.keevo.payments.checkout.application.service.PaymentView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Story 21.1 — the owner pays the plan by Mobile Money (« Mon plan »). ZERO business logic. OWNER of the company only
 * (store members and the super-admin: 403). Open whatever the plan or its expiry (core: abonnement).
 */
@Tag(name = "Payments", description = "Self-service plan payment (Mobile Money)")
@EntitlementExempt("core: abonnement")
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentsController {

    private final CheckoutService checkout;

    public PaymentsController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    @Operation(summary = "Start the payment of a plan", description = """
            Prices planCode × cycleCode through the offer catalogue (best discount, promoCode optional) and freezes that
            price with the request. A 0 FCFA price (free offer) is confirmed at once (status CONFIRMED). Otherwise the
            Mobile Money collection starts on phoneNumber with method ORANGE_MONEY or MTN_MOMO: status PENDING and
            nextStep OTP (send the SMS code to /authorize) or USSD (validate on the phone, dial ussdCode when given),
            then poll GET /payments/{id}. One payment in flight per company: 409 PAYMENT_IN_PROGRESS.
            Story 21.3: packCode (instead of planCode/cycleCode) buys a usage pack at its catalogue price (no promo
            code); useCredit=true applies the account credit first (creditAppliedXaf), the provider collects the rest
            (amountXaf); a price fully covered by the credit is confirmed at once (method and phone not required).
            """, security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/checkout")
    public ResponseEntity<ApiResponseWrapper<PaymentView>> checkout(
            @RequestBody(required = false) CheckoutRequest body) {
        UUID actor = owner();
        CheckoutRequest b = body == null ? new CheckoutRequest(null, null, null, null, null) : body;
        return ok(checkout.start(new CheckoutService.StartCommand(TenantContext.getCurrentTenant(), actor,
                b.planCode(), b.cycleCode(), b.promoCode(), b.method(), b.phoneNumber(), b.packCode(),
                b.useCredit())));
    }

    @Operation(summary = "Send the code received by SMS (nextStep OTP)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{id}/authorize")
    public ResponseEntity<ApiResponseWrapper<PaymentView>> authorize(@PathVariable UUID id,
                                                                    @RequestBody(required = false)
                                                                    AuthorizeRequest body) {
        owner();
        return ok(checkout.authorize(TenantContext.getCurrentTenant(), id, body == null ? null : body.code()));
    }

    @Operation(summary = "A payment of the company, refreshed from the provider while PENDING",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<PaymentView>> status(@PathVariable UUID id) {
        owner();
        return ok(checkout.status(TenantContext.getCurrentTenant(), id));
    }

    @Operation(summary = "Cancel the payment in flight (a payment confirmed later by the operator is still honoured)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponseWrapper<PaymentView>> cancel(@PathVariable UUID id) {
        owner();
        return ok(checkout.cancel(TenantContext.getCurrentTenant(), id));
    }

    @Operation(summary = "The payment in flight of the company (data null when none)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/current")
    public ResponseEntity<ApiResponseWrapper<PaymentView>> current() {
        owner();
        return ok(checkout.current(TenantContext.getCurrentTenant()));
    }

    private static UUID owner() {
        if (!CurrentActor.isOwner()) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, "Seul le propriétaire peut payer l'abonnement");
        }
        return CurrentActor.actorId();
    }

    private static <T> ResponseEntity<ApiResponseWrapper<T>> ok(T value) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(value));
    }
}
