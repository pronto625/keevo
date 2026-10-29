package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.ReactivateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeCountPort;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * ReactivateEmployeeService — Restores a previously deactivated employee.
 *
 * <p>Sets employee status back to ACTIVE and reactivates the user-tenant membership.
 *
 * <p>v1s-14-13: reactivating an INACTIVE employee grows the active count, so it enforces the
 * plan employee limit exactly like {@link CreateEmployeeService}: the {@code employee-limit:<schema>}
 * lock is taken first (before reading the employee and the count) so a reactivation and a
 * concurrent create — or two reactivations — at max−1 serialise.
 */
@Service
public class ReactivateEmployeeService implements ReactivateEmployeeUseCase {

    private final EmployeeRepository employeeRepository;
    private final UserMembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;
    private final PlanLimitCheck planLimit;
    private final EmployeeCountPort employeeCountPort;
    private final TenantPlanQuery tenantPlanQuery;
    private final TransactionLockPort transactionLock;

    public ReactivateEmployeeService(EmployeeRepository employeeRepository,
                                      UserMembershipRepository membershipRepository,
                                      TenantRepository tenantRepository,
                                      PlanLimitCheck planLimit,
                                      EmployeeCountPort employeeCountPort,
                                      TenantPlanQuery tenantPlanQuery,
                                      TransactionLockPort transactionLock) {
        this.employeeRepository = employeeRepository;
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
        this.planLimit = planLimit;
        this.employeeCountPort = employeeCountPort;
        this.tenantPlanQuery = tenantPlanQuery;
        this.transactionLock = transactionLock;
    }

    @Override
    @Transactional
    public void execute(ReactivateEmployeeCommand command) {
        String currentSchema = TenantContext.getCurrentTenant();
        if (currentSchema == null) {
            throw new IllegalStateException(
                    "TenantContext required to reactivate employee " + command.employeeId());
        }

        // v1s-14-13 — same per-tenant lock as CreateEmployeeService, taken BEFORE reading the
        // employee: two concurrent reactivations of the same employee serialise and the second
        // reads ACTIVE (no re-count), and a concurrent create's commit is visible to the count.
        transactionLock.lock("employee-limit:" + currentSchema);

        Employee employee = employeeRepository.findById(command.employeeId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // Reactivate the membership of THIS tenant only (v1s-13-9 follow-up: a membership the
        // user holds in another shop — possibly deactivated there — must stay untouched).
        UUID tenantId = tenantRepository.findBySchemaName(currentSchema)
                .map(Tenant::getId)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND));

        // Enforce the plan limit only when the reactivation actually grows the active count:
        // an already-ACTIVE employee (idempotent REACTIVATE_EMPLOYEE replay) is not re-counted.
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            var plan = tenantPlanQuery.findCurrentPlan(TenantContext.getCurrentTenant())
                    .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
            int currentCount = employeeCountPort.countActiveEmployees();
            planLimit.checkEmployeeLimit(plan, currentCount);
        }

        employeeRepository.updateStatus(command.employeeId(), EmployeeStatus.ACTIVE);

        membershipRepository.reactivate(employee.getUserId(), tenantId);
    }
}
