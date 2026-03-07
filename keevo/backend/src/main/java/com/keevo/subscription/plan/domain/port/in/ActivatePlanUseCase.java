package com.keevo.subscription.plan.domain.port.in;

/**
 * ActivatePlanUseCase — Input port: Super Admin activates or upgrades a tenant plan.
 */
public interface ActivatePlanUseCase {
    void execute(ActivatePlanCommand command);
}
