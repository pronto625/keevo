package com.keevo.admin.subscription.domain.port.in;

/** DowngradeToFreeUseCase — super admin moves a tenant back to the FREE plan (Story 9-2). */
public interface DowngradeToFreeUseCase {

    void downgradeToFree(DowngradeToFreeCommand command);
}
