package com.keevo.invoicing.invoice.domain.model;

import java.util.UUID;

/**
 * The client of an invoice (Story 14.1): free text — {@code name} required; NIU (for a business client), address and
 * phone optional. {@code clientId} is reserved for the CRM picker (Story 14.3): no foreign key, never required.
 */
public record ClientInfo(UUID clientId, String name, String niu, String address, String phone) {

    public ClientInfo {
        name = InvoicingSettings.trimToNull(name);
        niu = InvoicingSettings.trimToNull(niu);
        address = InvoicingSettings.trimToNull(address);
        phone = InvoicingSettings.trimToNull(phone);
    }
}
