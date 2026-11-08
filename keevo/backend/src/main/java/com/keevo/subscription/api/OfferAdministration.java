package com.keevo.subscription.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.10 — super-admin administration of the commercial offer (ADR-05, FUS-FR40): plans, billing cycles, prices,
 * trial policy and discounts, all data. Every write is validated (French messages: 422 {@code VALIDATION_ERROR} with
 * {@code details.<field>}, 404 {@code NOT_FOUND}, 409 {@code INVALID_STATE}), journaled in
 * {@code public.offer_change_log} in the same transaction, and seen by the next request (no cache on prices or
 * discounts; plan writes invalidate the plan catalogue cache). Archiving never touches the companies already on a
 * plan; codes are never deleted.
 *
 * <p>Codes travel in the API as identifiers (the dashboard shows the labels). Implemented by
 * {@code subscription.offer}.
 */
public interface OfferAdministration {

    /** The whole catalogue: plans (with prices and company counts), cycles, settings. */
    CatalogueView catalogue();

    /** Every billing cycle (archived included), in display order — labels of payments and revenue. */
    List<CycleItem> cycles();

    /** One billing cycle (archived included), empty when unknown. */
    java.util.Optional<CycleItem> cycle(String code);

    PlanItem createPlan(PlanInput input, UUID actorId);

    PlanItem updatePlan(String code, PlanUpdate input, UUID actorId);

    PlanItem archivePlan(String code, UUID actorId);

    PlanItem unarchivePlan(String code, UUID actorId);

    /** Display order of the plans: every plan code, each once, in the new order. */
    CatalogueView reorderPlans(List<String> codes, UUID actorId);

    CycleItem createCycle(CycleInput input, UUID actorId);

    CycleItem updateCycle(String code, CycleInput input, UUID actorId);

    CycleItem archiveCycle(String code, UUID actorId);

    CycleItem unarchiveCycle(String code, UUID actorId);

    PriceItem setPrice(String planCode, String cycleCode, Long amountXaf, UUID actorId);

    void deletePrice(String planCode, String cycleCode, UUID actorId);

    /** Price changes, newest first, optionally for one plan and/or one cycle. */
    List<PriceChangeView> priceHistory(String planCode, String cycleCode, Integer limit);

    SettingsView updateSettings(SettingsInput input, UUID actorId);

    List<DiscountView> discounts(boolean includeArchived);

    DiscountView createDiscount(DiscountInput input, UUID actorId);

    DiscountView updateDiscount(UUID id, DiscountInput input, UUID actorId);

    DiscountView archiveDiscount(UUID id, UUID actorId);

    /**
     * The change journal, newest first. Keyset cursor: the {@code occurredAt} and {@code id} of the last line read
     * ({@code before} null = newest; {@code beforeId} null = strictly before {@code before}).
     */
    List<ChangeView> changes(Instant before, UUID beforeId, Integer limit);

    // ── views ─────────────────────────────────────────────────────────────────

    record CatalogueView(List<PlanItem> plans, List<CycleItem> cycles, SettingsView settings) {
    }

    /**
     * @param kind        {@code FREE | TRIAL | PAID}
     * @param kindLabel   French name of the kind
     * @param tenantCount non-deleted companies on the plan
     * @param prices      the plan's prices, in cycle order (paid plans only)
     */
    record PlanItem(String code, String label, String description, String kind, String kindLabel, int sortOrder,
                    boolean visible, boolean archived, Instant archivedAt, long tenantCount, List<PriceItem> prices) {
    }

    record PriceItem(String cycleCode, String cycleLabel, int months, long amountXaf, Instant updatedAt) {
    }

    record CycleItem(String code, String label, int months, int sortOrder, boolean archived, Instant archivedAt) {
    }

    record SettingsView(String freePlanCode, String freePlanLabel, String trialPlanCode, String trialPlanLabel,
                        int trialMonths, boolean trialEnabled, String defaultPaidPlanCode,
                        String defaultPaidPlanLabel) {
    }

    /**
     * @param changedByLabel the author's name, « Super admin » when unnamed, « Données de départ » for the seed
     */
    record PriceChangeView(String planCode, String planLabel, String cycleCode, String cycleLabel, Long oldAmountXaf,
                           Long newAmountXaf, String changedByLabel, Instant changedAt) {
    }

    /**
     * @param scope       {@code PROMOTION | TENANT | PROMO_CODE}
     * @param live        not archived and inside its window now
     * @param tenantName  the company of a TENANT discount
     */
    record DiscountView(UUID id, String scope, String scopeLabel, String label, BigDecimal percentOff,
                        Long amountOffXaf, List<String> planCodes, List<String> planLabels, List<String> cycleCodes,
                        List<String> cycleLabels, Instant startsAt, Instant endsAt, UUID tenantId, String tenantName,
                        String promoCode, boolean archived, Instant archivedAt, boolean live, Instant createdAt) {
    }

    /**
     * @param id          keyset cursor only (never shown)
     * @param entityLabel French name of what changed
     * @param actorLabel  the author's name
     */
    record ChangeView(UUID id, String entity, String entityLabel, String summary, String actorLabel, Instant occurredAt) {
    }

    // ── inputs ────────────────────────────────────────────────────────────────

    /**
     * @param kind            {@code FREE | TRIAL | PAID}
     * @param visible         offered to customers ({@code null} = true)
     * @param copyProfileFrom plan whose limits, modules and quotas the new plan starts with; {@code null} = the free
     *                        plan of the settings (fail closed)
     */
    record PlanInput(String code, String label, String description, String kind, Boolean visible,
                     String copyProfileFrom) {
    }

    /** {@code null} fields keep the stored value. */
    record PlanUpdate(String label, String description, String kind, Boolean visible) {
    }

    /** @param sortOrder {@code null} = after the last cycle (create) / unchanged (update) */
    record CycleInput(String code, String label, Integer months, Integer sortOrder) {
    }

    /** {@code null} fields keep the stored value. */
    record SettingsInput(String freePlanCode, String trialPlanCode, Integer trialMonths, Boolean trialEnabled,
                         String defaultPaidPlanCode) {
    }

    /**
     * @param planCodes  empty or null = every plan
     * @param cycleCodes empty or null = every cycle
     */
    record DiscountInput(String scope, String label, BigDecimal percentOff, Long amountOffXaf, List<String> planCodes,
                         List<String> cycleCodes, Instant startsAt, Instant endsAt, UUID tenantId,
                         String promoCode) {
    }
}
