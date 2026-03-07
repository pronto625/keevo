package com.keevo.subscription.plan.domain.port.out;

/**
 * UserCountPort — Output port: count employees (role=EMPLOYEE) in the current tenant schema.
 *
 * <p>Temporary count-only port. Epic 3 will introduce a full UserRepository
 * — this port will be superseded at that point.
 * Counts ONLY users with role='EMPLOYEE' (not OWNER).
 */
public interface UserCountPort {
    int countEmployees();
}
