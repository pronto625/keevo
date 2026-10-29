package com.keevo.identity.auth.adapter.in.api;

import com.keevo.identity.api.TenantView;
import com.keevo.identity.auth.domain.model.Tenant;

/** Maps the internal tenant aggregate to its published view (Story 12.2d). */
final class TenantViews {

    private TenantViews() {}

    static TenantView of(Tenant t) {
        return new TenantView(t.getId(), t.getCode(), t.getSchemaName(), t.getName(), t.getStatus(),
                t.getPlanType(), t.getCreatedAt(), t.getDeletionScheduledAt());
    }
}
