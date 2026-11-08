package com.keevo.subscription.offer.domain.port.out;

import com.keevo.subscription.offer.domain.model.BillingCycle;
import com.keevo.subscription.offer.domain.model.Discount;
import com.keevo.subscription.offer.domain.model.OfferChange;
import com.keevo.subscription.offer.domain.model.OfferEntity;
import com.keevo.subscription.offer.domain.model.PlanPrice;
import com.keevo.subscription.offer.domain.model.PriceChange;
import com.keevo.subscription.offer.domain.model.TenantPlanRef;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.10 — persistence of the offer catalogue beyond the plans themselves (billing cycles, prices and their
 * history, discounts, change journal) on {@code public}. The plans go through {@code PlanCatalogStore}. Every statement
 * runs on the caller's transaction; every read is one set-based query (no per-row lookup).
 */
public interface OfferStore {

    // ── cycles ──

    /** Every cycle (archived included), in display order. */
    List<BillingCycle> cycles();

    Optional<BillingCycle> cycle(String code);

    /** Inserts a cycle; {@code false} when the code already exists (nothing written). */
    boolean insertCycle(BillingCycle cycle);

    /** Updates label, months, order and archive instant of a cycle. */
    void updateCycle(BillingCycle cycle);

    /** Whether a price or a recorded payment uses this cycle (one EXISTS query). */
    boolean cycleInUse(String code);

    // ── prices ──

    /** Every price. */
    List<PlanPrice> prices();

    /** The prices of one plan. */
    List<PlanPrice> prices(String planCode);

    /** Locks the plan row ({@code FOR UPDATE}): serialises the price writes of a plan, first creation included. */
    void lockPlan(String planCode);

    /** One price. */
    Optional<PlanPrice> price(String planCode, String cycleCode);

    /** The current price, read with a row lock ({@code FOR UPDATE}); empty when there is no price. */
    Optional<PlanPrice> lockPrice(String planCode, String cycleCode);

    void upsertPrice(String planCode, String cycleCode, long amountXaf, UUID actorId);

    void deletePrice(String planCode, String cycleCode);

    /** Appends a history row ({@code old} null = created, {@code next} null = removed). */
    void recordPriceChange(String planCode, String cycleCode, Long old, Long next, UUID actorId);

    /** Price history, newest first, optionally narrowed to a plan and/or a cycle. */
    List<PriceChange> priceHistory(String planCode, String cycleCode, int limit);

    // ── discounts ──

    /** Every discount (archived ones when asked), newest first, at most {@code limit}. */
    List<Discount> discounts(boolean includeArchived, int limit);

    Optional<Discount> discount(UUID id);

    /**
     * One query: the discounts live at {@code now} a customer may get — every promotion, the discounts of
     * {@code tenantId} (when not null) and the promo code {@code promoCode} (case-insensitive, when not null).
     */
    List<Discount> liveCandidates(UUID tenantId, String promoCode, Instant now);

    /** Whether a non-archived discount other than {@code exceptId} holds this promo code (case-insensitive). */
    boolean promoCodeTaken(String promoCode, UUID exceptId);

    /** Inserts a discount and returns its id. */
    UUID insertDiscount(Discount discount, UUID actorId);

    /** Replaces every field of a discount (archive instant included). */
    void updateDiscount(Discount discount, UUID actorId);

    // ── journal ──

    void journal(OfferEntity entity, String entityKey, String summary, UUID actorId);

    /**
     * Journal lines newest first (order {@code occurred_at DESC, id DESC}) with the author's name, after the keyset
     * cursor: strictly before {@code (before, beforeId)}; {@code beforeId} null = strictly before {@code before};
     * {@code before} null = from the newest.
     */
    List<OfferChange> changes(Instant before, UUID beforeId, int limit);

    // ── plans and companies ──

    /** Inserts a new plan; {@code false} when the code already exists (nothing written). */
    boolean insertPlan(com.keevo.shared.capability.entitlement.PlanDefinition plan);

    /** Non-deleted companies per plan code (one GROUP BY). */
    Map<String, Long> tenantCountsByPlan();

    /** Non-deleted companies on this plan. */
    long tenantsOnPlan(String planCode);

    /** Copies the profile, paid-module decisions and quotas of {@code source} onto {@code target} (three statements). */
    void copyProfile(String source, String target);

    /** Sets {@code plans.sort_order} to 10, 20, 30… in the given order (one statement). */
    void reorderPlans(List<String> codes);

    /** A non-deleted company and its plan. */
    Optional<TenantPlanRef> tenant(UUID tenantId);

    /** The company of a tenant schema and its plan. */
    Optional<TenantPlanRef> tenantBySchema(String schemaName);

    /** Company names by id (one query). */
    Map<UUID, String> tenantNames(Collection<UUID> ids);
}
