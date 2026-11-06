package com.keevo.invoicing.invoice.domain.model;

import java.util.UUID;

/**
 * The client of an invoice (Story 14.1): free text — {@code name} required; NIU (for a business client), address and
 * phone optional. {@code clientId} links a CRM client (Story 14.3, set only when {@code crm} vouched for it): no foreign key, never required.
 *
 * <p>Story 20.5 (Bref fields, copied onto the document, optional): {@code rccm}, {@code email}, {@code contactName}
 * (the person to contact), {@code website}.
 */
public record ClientInfo(UUID clientId, String name, String niu, String address, String phone, String rccm,
                         String email, String contactName, String website) {

    public ClientInfo {
        name = InvoicingSettings.trimToNull(name);
        niu = InvoicingSettings.trimToNull(niu);
        address = InvoicingSettings.trimToNull(address);
        phone = InvoicingSettings.trimToNull(phone);
        rccm = InvoicingSettings.trimToNull(rccm);
        email = InvoicingSettings.trimToNull(email);
        contactName = InvoicingSettings.trimToNull(contactName);
        website = InvoicingSettings.trimToNull(website);
    }

    /** Pre-20.5 shape: no RCCM, email, contact nor website. */
    public ClientInfo(UUID clientId, String name, String niu, String address, String phone) {
        this(clientId, name, niu, address, phone, null, null, null, null);
    }

    /**
     * Story 20.5 — the same client with its RCCM and email filled from the CRM when they were not typed ({@code null}
     * keeps the typed value).
     */
    public ClientInfo withCrmDetails(String crmRccm, String crmEmail) {
        return new ClientInfo(clientId, name, niu, address, phone, rccm != null ? rccm : crmRccm,
                email != null ? email : crmEmail, contactName, website);
    }

    /** The same client with another CRM link, name and phone (CRM resolution, Story 14.3); every other field kept. */
    public ClientInfo resolved(UUID newClientId, String newName, String newPhone) {
        return new ClientInfo(newClientId, newName, niu, address, newPhone, rccm, email, contactName, website);
    }
}
