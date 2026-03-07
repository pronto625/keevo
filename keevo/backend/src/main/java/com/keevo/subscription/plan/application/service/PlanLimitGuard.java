package com.keevo.subscription.plan.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * PlanLimitGuard — Pure plan-limit enforcement service (no HTTP imports).
 *
 * <p>GoF Pattern: <b>Strategy</b> over {@link PlanType}. Each plan type carries its own
 * numeric limits via {@code getMaxStores()}, {@code getMaxProducts()}, {@code getMaxEmployees()}.
 * This service checks the given current count against those limits. A new plan tier
 * (PRO, ENTERPRISE) only requires adding a new {@link PlanType} constant — no changes here.
 *
 * <p>Usage:
 * <pre>{@code
 *   planLimitGuard.checkStoreLimit(subscription.getPlanType(), currentStoreCount);
 * }</pre>
 */
@Service
public class PlanLimitGuard {

    /**
     * Check if a new store can be created under the given plan.
     *
     * @param planType      current plan type
     * @param currentStores current number of active stores
     * @throws DomainException PLAN_LIMIT_EXCEEDED if limit reached
     */
    public void checkStoreLimit(PlanType planType, int currentStores) {
        checkLimit(currentStores, planType.getMaxStores(), "stores");
    }

    /**
     * Check if a new product can be created under the given plan.
     *
     * @param planType        current plan type
     * @param currentProducts current number of active products
     * @throws DomainException PLAN_LIMIT_EXCEEDED if limit reached
     */
    public void checkProductLimit(PlanType planType, int currentProducts) {
        checkLimit(currentProducts, planType.getMaxProducts(), "products");
    }

    /**
     * Check if a new employee can be invited under the given plan.
     *
     * @param planType         current plan type
     * @param currentEmployees current number of active employees
     * @throws DomainException PLAN_LIMIT_EXCEEDED if limit reached
     */
    public void checkEmployeeLimit(PlanType planType, int currentEmployees) {
        checkLimit(currentEmployees, planType.getMaxEmployees(), "employees");
    }

    // ── Private helper ────────────────────────────────────────────────────────

    private void checkLimit(int current, int max, String entity) {
        if (current >= max) {
            throw new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED,
                    entity + " limit reached",
                    Map.of("entity", entity, "limit", max, "current", current));
        }
    }
}
