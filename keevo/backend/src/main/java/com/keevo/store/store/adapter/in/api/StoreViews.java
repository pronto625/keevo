package com.keevo.store.store.adapter.in.api;

import com.keevo.store.api.StoreView;
import com.keevo.store.store.domain.model.Store;

/** Maps the internal store to its published view (Story 12.2d). */
final class StoreViews {

    private StoreViews() {}

    static StoreView of(Store s) {
        return new StoreView(s.id(), s.name(), s.type(), s.address(), s.phone(), s.isActive(),
                s.createdAt(), s.updatedAt());
    }
}
