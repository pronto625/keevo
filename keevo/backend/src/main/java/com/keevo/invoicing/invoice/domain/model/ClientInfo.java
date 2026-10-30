package com.keevo.invoicing.invoice.domain.model;

import java.util.UUID;

/**
 * The client of an invoice (Story 14.1): free text — {@code name} required; NIU (for a business client), address and
 * phone optional. {@code clientId} links a CRM client (Story 14.3, set only when {@code crm} vouched for it): no foreign key, never required.
 */
public record ClientInfo(UUID clientId, String name, String niu, String address, String phone) {

    public ClientInfo {
        name = InvoicingSettings.trimToNull(name);
        niu = InvoicingSettings.trimToNull(niu);
        address = InvoicingSettings.trimToNull(address);
        phone = InvoicingSettings.trimToNull(phone);
    }
}
