package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.ClientView;
import com.keevo.crm.api.SupplierView;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.entity.Supplier;

import java.util.List;
import java.util.UUID;

/** Maps internal crm entities to the published views (Story 12.2a). */
final class ContactViews {

    private ContactViews() {}

    static ClientView of(Client c) {
        return new ClientView(c.id(), c.name(), c.phone(), c.email(), c.notes(), c.archived(),
                c.createdAt(), c.updatedAt());
    }

    /** {@code productIds} is passed through as-is (null stays null, like the former crm web DTO). */
    static SupplierView of(Supplier s, List<UUID> productIds) {
        return new SupplierView(s.id(), s.name(), s.phone(), s.email(), s.archived(),
                productIds, s.createdAt(), s.updatedAt());
    }
}
