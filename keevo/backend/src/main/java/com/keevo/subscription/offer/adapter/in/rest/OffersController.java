package com.keevo.subscription.offer.adapter.in.rest;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.subscription.api.OfferPricing;
import com.keevo.subscription.offer.application.service.OfferPricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 21.10 — the offers a company can buy (« Mon plan »): {@code GET /api/v1/subscription/offers}. ZERO business
 * logic. Tenant members only (OWNER or a store member); the super-admin has no company: 403.
 */
@Tag(name = "Subscription", description = "Plan limits and account lifecycle")
@EntitlementExempt("core: abonnement")
@RestController
@RequestMapping("/api/v1/subscription")
public class OffersController {

    private final OfferPricingService pricing;

    public OffersController(OfferPricingService pricing) {
        this.pricing = pricing;
    }

    @Operation(summary = "Offers of the company: plans, prices per cycle with the best discount, current plan",
            description = """
                Visible live free and paid plans in display order, with their limits (null = unlimited), paid
                modules and usage quotas, and for a paid plan the price of every live billing cycle: base price,
                effective price after the best single discount (promotion, company discount or the promo code
                given), price per month and the discount applied (label, percent or amount, end date). promoCode
                is optional and case-insensitive: promoCode.valid=false with a French message when it is unknown or
                expired. Prices are read live (a super-admin change shows at the next call).
                """,
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/offers")
    public ResponseEntity<ApiResponseWrapper<OfferPricing.OffersView>> offers(
            @RequestParam(required = false) String promoCode) {
        if (!(CurrentActor.isOwner() || CurrentActor.isStoreMember())) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "This endpoint requires a tenant member role (OWNER, MANAGER, EMPLOYEE or VIEWER)");
        }
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                pricing.offersForSchema(TenantContext.getCurrentTenant(), promoCode)));
    }
}
