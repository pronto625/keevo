package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.SupplierView;
import com.keevo.crm.contact.domain.entity.Supplier;

import java.util.List;
import java.util.UUID;

/** Test-only access to the package-private crm view mapper (Story 12.2b). */
public final class CrmContactApiTestSupport {

    private CrmContactApiTestSupport() {}

    /** The published supplier view, mapped exactly as the crm adapters do. */
    public static SupplierView supplierView(Supplier supplier, List<UUID> productIds) {
        return ContactViews.of(supplier, productIds);
    }
}
