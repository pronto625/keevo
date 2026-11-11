package com.keevo.identity.employee.application.service;

import com.keevo.identity.employee.domain.port.out.EmployeeCountPort;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The employee ceiling of the current company's plan, one rule for every way a member arrives: a creation by the owner
 * (Story 3.5) and the acceptance of an invitation (Story 19.6b).
 *
 * <p>v1s-13-13 — the per-tenant transaction lock is taken BEFORE the count, so two concurrent arrivals at max−1 cannot
 * both pass (the count sees the winner's commit, which happens before the lock is released). Story 19.7b — tenant and
 * target-store counts in one query, the per-store limit after the tenant-wide one. Complexity: one advisory lock, one
 * plan read, one count.
 */
@Component
public class EmployeePlanLimit {

    private final TransactionLockPort transactionLock;
    private final TenantPlanQuery tenantPlanQuery;
    private final EmployeeCountPort employeeCountPort;
    private final PlanLimitCheck planLimit;

    public EmployeePlanLimit(TransactionLockPort transactionLock, TenantPlanQuery tenantPlanQuery,
                             EmployeeCountPort employeeCountPort, PlanLimitCheck planLimit) {
        this.transactionLock = transactionLock;
        this.tenantPlanQuery = tenantPlanQuery;
        this.employeeCountPort = employeeCountPort;
        this.planLimit = planLimit;
    }

    /**
     * Locks the current company's member arrivals until the end of the transaction, then refuses with
     * {@code PLAN_LIMIT_EXCEEDED} when one more member in {@code storeId} would exceed the plan.
     */
    public void lockAndCheck(UUID storeId) {
        lock();
        check(storeId);
    }

    /**
     * Takes the per-company lock of member arrivals (until the end of the transaction). Story 19.6b — every path takes
     * it FIRST, before any row lock (one lock order: no deadlock between an acceptance and a re-invitation).
     */
    public void lock() {
        transactionLock.lock("employee-limit:" + TenantContext.getCurrentTenant());
    }

    /** The ceiling check alone, under the lock already taken by {@link #lock()}. */
    public void check(UUID storeId) {
        String schema = TenantContext.getCurrentTenant();
        var plan = tenantPlanQuery.findCurrentPlan(schema)
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        var counts = employeeCountPort.countActive(storeId, null);
        planLimit.checkEmployeeLimit(plan, counts.tenant());
        if (plan.maxEmployeesPerStore() != null && storeId != null) {
            planLimit.checkStoreEmployeeLimit(plan, counts.store());
        }
    }
}
