package com.keevo.payments.appstore.adapter.in.rest;

import com.keevo.payments.appstore.application.service.AppStoreSubscriptionService;
import com.keevo.payments.appstore.application.service.AppStoreSubscriptionService.ProductView;
import com.keevo.payments.appstore.application.service.AppStoreSubscriptionView;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Story 21.2 — the owner's App Store purchases (« Mon plan » on iPhone). ZERO business logic. OWNER of the company only
 * (store members and the super-admin: 403). Open whatever the plan or its expiry (core: abonnement).
 */
@Tag(name = "Payments", description = "Self-service plan payment (App Store in-app purchases)")
@EntitlementExempt("core: abonnement")
@RestController
@RequestMapping("/api/v1/payments/app-store")
public class AppStoreController {

    private final AppStoreSubscriptionService subscriptions;

    public AppStoreController(AppStoreSubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Operation(summary = "App Store products offered (StoreKit identifiers and the plan × cycle each one grants)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/products")
    public ResponseEntity<ApiResponseWrapper<List<ProductView>>> products() {
        owner();
        return ok(subscriptions.products());
    }

    @Operation(summary = "The App Store subscription of the company (data null when none)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/subscription")
    public ResponseEntity<ApiResponseWrapper<AppStoreSubscriptionView>> subscription() {
        owner();
        return ok(subscriptions.current(TenantContext.getCurrentTenant()).orElse(null));
    }

    @Operation(summary = "Read the owner's App Store purchases now (after a purchase or a restore) and apply them",
            description = """
                    Reads RevenueCat for the signed-in owner (app_user_id = user id) and binds every active App Store
                    subscription mapped to an offer to this company, then applies its plan until Apple's expiry.
                    503 PAYMENT_UNAVAILABLE (not configured), 502 PAYMENT_PROVIDER_ERROR, 404 PAYMENT_NOT_FOUND
                    (nothing active), 409 INVALID_STATE (not offered, or bound to another company).
                    """, security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/sync")
    public ResponseEntity<ApiResponseWrapper<AppStoreSubscriptionView>> sync() {
        UUID actor = owner();
        return ok(subscriptions.sync(TenantContext.getCurrentTenant(), actor));
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
