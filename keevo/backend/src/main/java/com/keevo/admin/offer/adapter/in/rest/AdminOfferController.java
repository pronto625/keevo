package com.keevo.admin.offer.adapter.in.rest;

import com.keevo.admin.offer.adapter.in.rest.dto.OfferRequests.CycleRequest;
import com.keevo.admin.offer.adapter.in.rest.dto.OfferRequests.DiscountRequest;
import com.keevo.admin.offer.adapter.in.rest.dto.OfferRequests.PlanOrderRequest;
import com.keevo.admin.offer.adapter.in.rest.dto.OfferRequests.PlanRequest;
import com.keevo.admin.offer.adapter.in.rest.dto.OfferRequests.PlanUpdateRequest;
import com.keevo.admin.offer.adapter.in.rest.dto.OfferRequests.PriceRequest;
import com.keevo.admin.offer.adapter.in.rest.dto.OfferRequests.SettingsRequest;
import com.keevo.admin.offer.adapter.in.rest.dto.OfferRequests.StoreProductRequest;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.subscription.api.OfferAdministration;
import com.keevo.subscription.api.OfferAdministration.CatalogueView;
import com.keevo.subscription.api.OfferAdministration.ChangeView;
import com.keevo.subscription.api.OfferAdministration.CycleInput;
import com.keevo.subscription.api.OfferAdministration.CycleItem;
import com.keevo.subscription.api.OfferAdministration.DiscountInput;
import com.keevo.subscription.api.OfferAdministration.DiscountView;
import com.keevo.subscription.api.OfferAdministration.PlanInput;
import com.keevo.subscription.api.OfferAdministration.PlanItem;
import com.keevo.subscription.api.OfferAdministration.PlanUpdate;
import com.keevo.subscription.api.OfferAdministration.PriceChangeView;
import com.keevo.subscription.api.OfferAdministration.PriceItem;
import com.keevo.subscription.api.OfferAdministration.SettingsInput;
import com.keevo.subscription.api.OfferAdministration.SettingsView;
import com.keevo.subscription.api.OfferAdministration.StoreProductInput;
import com.keevo.subscription.api.OfferAdministration.StoreProductView;
import com.keevo.subscription.api.OfferPricing;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AdminOfferController — super-admin catalogue of the commercial offer (Story 21.10, ADR-05, FUS-FR40): plans,
 * billing cycles, prices and their history, trial policy, discounts, change journal and a price preview.
 *
 * <p>ZERO business logic: bodies to {@link OfferAdministration} / {@link OfferPricing} inputs.
 * {@link CurrentActor#requireSuperAdmin()} in every method (second barrier behind the SecurityConfig
 * {@code /api/v1/admin/**} rule). Errors: 422 {@code VALIDATION_ERROR} ({@code details.<field>}, French), 404
 * {@code NOT_FOUND}, 409 {@code INVALID_STATE} (duplicate code, kind change of a plan in use, archive of a plan used by
 * the settings). Every write is journaled ({@code GET /changes}) and seen by the next request.
 */
@Tag(name = "Admin — Offer catalogue", description = "Super Admin plans, billing cycles, prices, trial, discounts")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/offers")
public class AdminOfferController {

    private final OfferAdministration offers;
    private final OfferPricing pricing;

    public AdminOfferController(OfferAdministration offers, OfferPricing pricing) {
        this.offers = offers;
        this.pricing = pricing;
    }

    @Operation(summary = "The whole catalogue: plans (kind, visibility, archive, companies on it, prices per cycle), "
            + "cycles, settings (free / trial / default paid plan, trial length, trial on or off)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<CatalogueView>> catalogue() {
        CurrentActor.requireSuperAdmin();
        return ok(offers.catalogue());
    }

    // ── plans ─────────────────────────────────────────────────────────────────

    @Operation(summary = "Create a plan (code, label, kind FREE|TRIAL|PAID, visible, copyProfileFrom: limits, modules "
            + "and quotas copied from that plan, default the free plan); 409 when the code exists",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/plans")
    public ResponseEntity<ApiResponseWrapper<PlanItem>> createPlan(@RequestBody(required = false) PlanRequest body) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.createPlan(body == null ? null : new PlanInput(body.code(), body.label(), body.description(),
                body.kind(), body.visible(), body.copyProfileFrom()), CurrentActor.actorId()));
    }

    @Operation(summary = "Update a plan (label, description, kind, visible; absent = kept); 409 on a kind change "
            + "while companies or the settings use the plan",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/plans/{code}")
    public ResponseEntity<ApiResponseWrapper<PlanItem>> updatePlan(@PathVariable String code,
                                                                   @RequestBody(required = false)
                                                                   PlanUpdateRequest body) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.updatePlan(code, body == null ? null : new PlanUpdate(body.label(), body.description(),
                body.kind(), body.visible()), CurrentActor.actorId()));
    }

    @Operation(summary = "Archive a plan: closed to any new attribution, companies on it keep it; 409 when the "
            + "settings use it", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/plans/{code}/archive")
    public ResponseEntity<ApiResponseWrapper<PlanItem>> archivePlan(@PathVariable String code) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.archivePlan(code, CurrentActor.actorId()));
    }

    @Operation(summary = "Unarchive a plan", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/plans/{code}/unarchive")
    public ResponseEntity<ApiResponseWrapper<PlanItem>> unarchivePlan(@PathVariable String code) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.unarchivePlan(code, CurrentActor.actorId()));
    }

    @Operation(summary = "Display order of the plans ({codes}: every plan, each once)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/plans/order")
    public ResponseEntity<ApiResponseWrapper<CatalogueView>> reorderPlans(
            @RequestBody(required = false) PlanOrderRequest body) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.reorderPlans(body == null ? null : body.codes(), CurrentActor.actorId()));
    }

    // ── cycles ────────────────────────────────────────────────────────────────

    @Operation(summary = "Create a billing cycle (code, label, months 1..36, sortOrder)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/cycles")
    public ResponseEntity<ApiResponseWrapper<CycleItem>> createCycle(@RequestBody(required = false) CycleRequest body) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.createCycle(cycleInput(body), CurrentActor.actorId()));
    }

    @Operation(summary = "Update a billing cycle (label, months, sortOrder; absent = kept)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/cycles/{code}")
    public ResponseEntity<ApiResponseWrapper<CycleItem>> updateCycle(@PathVariable String code,
                                                                     @RequestBody(required = false)
                                                                     CycleRequest body) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.updateCycle(code, cycleInput(body), CurrentActor.actorId()));
    }

    @Operation(summary = "Archive a billing cycle (no longer offered)", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/cycles/{code}/archive")
    public ResponseEntity<ApiResponseWrapper<CycleItem>> archiveCycle(@PathVariable String code) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.archiveCycle(code, CurrentActor.actorId()));
    }

    @Operation(summary = "Unarchive a billing cycle", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/cycles/{code}/unarchive")
    public ResponseEntity<ApiResponseWrapper<CycleItem>> unarchiveCycle(@PathVariable String code) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.unarchiveCycle(code, CurrentActor.actorId()));
    }

    // ── prices ────────────────────────────────────────────────────────────────

    @Operation(summary = "Set the price of a paid plan for a cycle ({amountXaf} 1..100 000 000), history kept",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/prices/{planCode}/{cycleCode}")
    public ResponseEntity<ApiResponseWrapper<PriceItem>> setPrice(@PathVariable String planCode,
                                                                  @PathVariable String cycleCode,
                                                                  @RequestBody(required = false) PriceRequest body) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.setPrice(planCode, cycleCode, body == null ? null : body.amountXaf(),
                CurrentActor.actorId()));
    }

    @Operation(summary = "Remove the price of a plan for a cycle (no longer sold for that cycle), history kept",
            security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/prices/{planCode}/{cycleCode}")
    public ResponseEntity<ApiResponseWrapper<Void>> deletePrice(@PathVariable String planCode,
                                                                @PathVariable String cycleCode) {
        CurrentActor.requireSuperAdmin();
        offers.deletePrice(planCode, cycleCode, CurrentActor.actorId());
        return ok(null);
    }

    @Operation(summary = "Price history, newest first (optional planCode / cycleCode, limit 1..200, default 50)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/prices/history")
    public ResponseEntity<ApiResponseWrapper<List<PriceChangeView>>> priceHistory(
            @RequestParam(required = false) String planCode,
            @RequestParam(required = false) String cycleCode,
            @RequestParam(required = false) Integer limit) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.priceHistory(planCode, cycleCode, limit));
    }

    // ── settings ──────────────────────────────────────────────────────────────

    @Operation(summary = "Offer settings: free plan, trial plan, trial length (months), trial on/off, default paid "
            + "plan (absent = kept)", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/settings")
    public ResponseEntity<ApiResponseWrapper<SettingsView>> updateSettings(
            @RequestBody(required = false) SettingsRequest body) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.updateSettings(body == null ? null : new SettingsInput(body.freePlanCode(),
                body.trialPlanCode(), body.trialMonths(), body.trialEnabled(), body.defaultPaidPlanCode()),
                CurrentActor.actorId()));
    }

    // ── discounts ─────────────────────────────────────────────────────────────

    @Operation(summary = "Discounts, newest first (archived ones with includeArchived=true)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/discounts")
    public ResponseEntity<ApiResponseWrapper<List<DiscountView>>> discounts(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.discounts(includeArchived));
    }

    @Operation(summary = "Create a discount (PROMOTION dated / TENANT one company / PROMO_CODE; a percentage or an "
            + "amount; plans and cycles, empty = all); 409 when the promo code is taken",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/discounts")
    public ResponseEntity<ApiResponseWrapper<DiscountView>> createDiscount(
            @RequestBody(required = false) DiscountRequest body) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.createDiscount(discountInput(body), CurrentActor.actorId()));
    }

    @Operation(summary = "Replace a discount (same body as the creation)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/discounts/{id}")
    public ResponseEntity<ApiResponseWrapper<DiscountView>> updateDiscount(@PathVariable UUID id,
                                                                           @RequestBody(required = false)
                                                                           DiscountRequest body) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.updateDiscount(id, discountInput(body), CurrentActor.actorId()));
    }

    @Operation(summary = "Archive a discount (stops at once)", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/discounts/{id}/archive")
    public ResponseEntity<ApiResponseWrapper<DiscountView>> archiveDiscount(@PathVariable UUID id) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.archiveDiscount(id, CurrentActor.actorId()));
    }

    // ── journal and preview ───────────────────────────────────────────────────

    @Operation(summary = "Catalogue change journal, newest first (French summary, author's name); keyset: before = "
            + "occurredAt and beforeId = id of the last line read (beforeId optional)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/changes")
    public ResponseEntity<ApiResponseWrapper<List<ChangeView>>> changes(
            @RequestParam(required = false) Instant before,
            @RequestParam(required = false) UUID beforeId,
            @RequestParam(required = false) Integer limit) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.changes(before, beforeId, limit));
    }

    @Operation(summary = "Price preview of a plan for a cycle, best discount applied (optional company, promo code) — "
            + "the price the payment would charge now", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/quote")
    public ResponseEntity<ApiResponseWrapper<OfferPricing.Quote>> quote(
            @RequestParam(required = false) String planCode,
            @RequestParam(required = false) String cycleCode,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String promoCode) {
        CurrentActor.requireSuperAdmin();
        return ok(pricing.quote(tenantId, planCode, cycleCode, promoCode));
    }

    // ── App Store products (Story 21.2) ───────────────────────────────────────

    @Operation(summary = "App Store products (inactive included): plan and cycle granted, Apple price (declared or "
            + "observed on a purchase), grid price and gap", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/store-products")
    public ResponseEntity<ApiResponseWrapper<List<StoreProductView>>> storeProducts() {
        CurrentActor.requireSuperAdmin();
        return ok(offers.storeProducts());
    }

    @Operation(summary = "Create or replace the mapping of an App Store product (planCode: live paid plan, cycleCode, "
            + "declaredPriceXaf optional, active default true)", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/store-products/{productId:.+}")
    public ResponseEntity<ApiResponseWrapper<StoreProductView>> saveStoreProduct(
            @PathVariable String productId, @RequestBody(required = false) StoreProductRequest body) {
        CurrentActor.requireSuperAdmin();
        return ok(offers.saveStoreProduct(productId, body == null ? null : new StoreProductInput(body.planCode(),
                body.cycleCode(), body.declaredPriceXaf(), body.active()), CurrentActor.actorId()));
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private static CycleInput cycleInput(CycleRequest body) {
        return body == null ? null : new CycleInput(body.code(), body.label(), body.months(), body.sortOrder());
    }

    private static DiscountInput discountInput(DiscountRequest body) {
        return body == null ? null : new DiscountInput(body.scope(), body.label(), body.percentOff(),
                body.amountOffXaf(), body.planCodes(), body.cycleCodes(), body.startsAt(), body.endsAt(),
                body.tenantId(), body.promoCode(), body.maxRedemptions());
    }

    private static <T> ResponseEntity<ApiResponseWrapper<T>> ok(T value) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(value));
    }
}
