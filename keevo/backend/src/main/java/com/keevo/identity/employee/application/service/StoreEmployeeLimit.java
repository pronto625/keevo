package com.keevo.identity.employee.application.service;

import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
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
 * Story 19.7b — the plan's per-store employee limit when an employee is <b>moved</b> to another store (Bref's member
 * limit is per shop): {@link UpdateEmployeeService} and {@link ReassignStoreService}. The plan is read first; only when its
 * per-store limit is finite, the same {@code employee-limit:<schema>} lock as creation and reactivation is taken, then
 * one indexed count of the target store's ACTIVE employees, the moved one excluded. An INACTIVE employee does not grow the
 * target store's active count: its reactivation checks it. Runs in the caller's transaction.
 */
@Component
public class StoreEmployeeLimit {

    private final PlanLimitCheck planLimit;
    private final EmployeeCountPort employeeCountPort;
    private final TenantPlanQuery tenantPlanQuery;
    private final TransactionLockPort transactionLock;

    public StoreEmployeeLimit(PlanLimitCheck planLimit, EmployeeCountPort employeeCountPort,
                              TenantPlanQuery tenantPlanQuery, TransactionLockPort transactionLock) {
        this.planLimit = planLimit;
        this.employeeCountPort = employeeCountPort;
        this.tenantPlanQuery = tenantPlanQuery;
        this.transactionLock = transactionLock;
    }

    /**
     * @throws DomainException PLAN_LIMIT_EXCEEDED ({@code scope = "store"}) when the target store is full
     */
    public void checkMove(Employee employee, UUID targetStore) {
        if (employee.getStatus() != EmployeeStatus.ACTIVE || targetStore == null
                || targetStore.equals(employee.getStoreId())) {
            return;
        }
        String schema = TenantContext.getCurrentTenant();
        var plan = tenantPlanQuery.findCurrentPlan(schema)
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        if (plan.maxEmployeesPerStore() == null) {
            return; // unlimited per store: no lock, no count
        }
        transactionLock.lock("employee-limit:" + schema);
        planLimit.checkStoreEmployeeLimit(plan,
                employeeCountPort.countActiveEmployeesInStore(targetStore, employee.getId()));
    }
}
