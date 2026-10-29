package com.keevo.store.store.adapter.in.api;

import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.store.api.StoreCommandPort;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreView;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.in.DeactivateStoreUseCase;
import com.keevo.store.store.domain.port.in.UpdateStoreUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;

/**
 * Test-only access to the package-private {@code store :: api} adapter (Story 12.2d), for tests of
 * other packages that drive the real adapter over a mocked repository / use cases.
 */
public final class StoreApiTestSupport {

    private StoreApiTestSupport() {}

    /** The real query adapter over the given repository. */
    public static StoreQueryPort queryPort(StoreRepository storeRepository) {
        return new StoreApiAdapter(storeRepository, null, null, null, null, null);
    }

    /** The real command adapter; unused delegates may be null. */
    public static StoreCommandPort commandPort(StoreRepository storeRepository,
                                               UpdateStoreUseCase update,
                                               DeactivateStoreUseCase deactivate,
                                               TenantPlanQuery tenantPlanQuery,
                                               PlanLimitCheck planLimit,
                                               TransactionLockPort transactionLock) {
        return new StoreApiAdapter(storeRepository, update, deactivate, tenantPlanQuery, planLimit,
                transactionLock);
    }

    /** The published view of a store, mapped exactly as the adapter does. */
    public static StoreView view(Store store) {
        return StoreViews.of(store);
    }
}
