package com.keevo.subscription.plan.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.shared.capability.entitlement.PlanView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.IntSupplier;

/**
 * PlanLimitGuard — Pure plan-limit enforcement service (no HTTP imports).
 *
 * <p>Story 16.8: the limits are those carried by the {@link PlanView} the caller read for this request — built from
 * the plan profile ({@link PlanProfiles}, {@code public.plan_profiles}) — so a super-admin change applies to the
 * next creation without a restart, and the check, its message and the CSV quota use the same values. {@code null} =
 * unlimited: no check (and, for products, no count). Existing records above a lowered limit are kept: only the next
 * creation/reactivation is refused. Refusal details: {@code entity}, {@code limit}, {@code current}, {@code plan}.
 */
@Service
public class PlanLimitGuard {

    private static final Logger log = LoggerFactory.getLogger(PlanLimitGuard.class);

    private final PlanProfiles profiles;

    public PlanLimitGuard(PlanProfiles profiles) {
        this.profiles = profiles;
    }

    /** @throws DomainException PLAN_LIMIT_EXCEEDED if the plan's store limit is reached */
    public void checkStoreLimit(PlanView plan, int currentStores) {
        checkLimit(plan, currentStores, plan.maxStores(), "stores");
    }

    /**
     * Product check that counts only when the plan's limit is finite, and fails closed: a count that cannot be read
     * refuses the creation ({@code PRODUCT_QUOTA_UNAVAILABLE}, 503).
     */
    public void checkProductLimit(PlanView plan, IntSupplier currentProducts) {
        Integer limit = plan.maxProducts();
        if (limit == null) {
            return;
        }
        checkLimit(plan, countProducts(currentProducts), limit, "products");
    }

    /**
     * Products that can still be created ({@code Integer.MAX_VALUE} when unlimited, never negative); counts only for
     * a finite limit and fails closed like {@link #checkProductLimit(PlanView, IntSupplier)}.
     */
    public int remainingProducts(PlanView plan, IntSupplier currentProducts) {
        Integer limit = plan.maxProducts();
        if (limit == null) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, limit - countProducts(currentProducts));
    }

    /** @throws DomainException PLAN_LIMIT_EXCEEDED if the plan's employee limit is reached */
    public void checkEmployeeLimit(PlanView plan, int currentEmployees) {
        checkLimit(plan, currentEmployees, plan.maxEmployees(), "employees");
    }

    /** The current profile of the plan type (limits {@code null} = unlimited), read live. */
    public PlanProfile profile(PlanType planType) {
        return profiles.profile(planType);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static int countProducts(IntSupplier currentProducts) {
        try {
            return currentProducts.getAsInt();
        } catch (RuntimeException e) {
            log.warn("Product quota: active products could not be counted ({}) — creation refused",
                    e.getClass().getSimpleName());
            throw new DomainException(ErrorCode.PRODUCT_QUOTA_UNAVAILABLE, "Product count unavailable");
        }
    }

    private static void checkLimit(PlanView plan, int current, Integer max, String entity) {
        if (max != null && current >= max) {
            throw new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED,
                    entity + " limit reached",
                    Map.of("entity", entity, "limit", max, "current", current, "plan", plan.planType().name()));
        }
    }
}
