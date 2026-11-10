// Story 21.5 — the « WhatsApp Business » module (whatsapp-cloud, paid, FUS-FR28): the company's own WhatsApp Business
// account (WABA) connected through Meta's embedded signup (access token encrypted at rest), Meta's single webhook routed
// to the company by phone_number_id (X-Hub-Signature-256 verified, idempotent on Meta's message id), the inbox
// (conversations, free-text replies inside the 24-hour window, approved templates outside it), the company's message
// templates with Meta's review status, and the invoice / quote sending by the bref_facture template from the owner's
// number (shared.capability.notification.TenantWhatsAppBusiness, consumed by invoicing). Depends on the shared core only;
// Meta's Graph API sits behind the MetaGraphClient port (meta / fake / disabled).
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared"}
)
package com.keevo.whatsappcloud;
