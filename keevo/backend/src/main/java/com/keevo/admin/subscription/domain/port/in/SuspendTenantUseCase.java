package com.keevo.admin.subscription.domain.port.in;

/** SuspendTenantUseCase — ACTIVE → SUSPENDED and revoke every member session (Story 9-2). */
public interface SuspendTenantUseCase {

    void suspend(SuspendTenantCommand command);
}
