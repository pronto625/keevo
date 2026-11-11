// Story 21.7 — the « Portail client » module (client-portal, free, FUS-FR29): an end client of the merchants signs in
// with a one-time code sent to his phone (identity :: api ClientPortalCodePort, the 19.2 machinery under its own
// purpose) — or, like Bref (reprise 11/11/2026), registers once with that code and a password and then signs in by
// phone or e-mail + password —, gets an opaque 24-hour session, and lists every invoice issued to that phone across
// merchants (with its lines and payments, as Bref shows them). It owns the
// cross-tenant index public.invoice_phone_index (shared.capability.portal.InvoicePhoneIndex, fed by invoicing at issue)
// and reads the documents through shared.capability.portal.PortalDocuments (implemented by invoicing). The merchant's
// switch is the owner activation of the module (« Mes modules »). Depends on the shared core and identity :: api only.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared", "identity :: api"}
)
package com.keevo.clientportal;
