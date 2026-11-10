package com.keevo.shared.capability.notification;

import java.util.function.Supplier;

/**
 * Story 21.5 — sending a document (invoice, quote) to a client from the company's own WhatsApp Business number, with
 * Meta's approved template {@code bref_facture} and the PDF as its header (Bref rule: never from the platform number).
 * Implemented by the {@code whatsapp-cloud} module; consumed by invoicing, which falls back to the platform
 * {@link WhatsAppSender} (free text with the public link) when {@link #canSendDocuments} is false.
 */
public interface TenantWhatsAppBusiness {

    /**
     * True when the company's documents leave from its own number now: module {@code whatsapp-cloud} fully enabled,
     * number connected and the document template approved by Meta. Cheap (cached entitlement, two indexed reads);
     * never throws (a failure reads false).
     */
    boolean canSendDocuments(String tenantSchema);

    /**
     * Sends the document. The PDF is rendered by {@code pdf} only once the account is known usable. Never throws for a
     * Meta outcome: {@link DeliveryResult#deliveredBy} when Meta accepted it, {@link DeliveryResult#uncertain} when it
     * did not answer in time (maybe delivered: never resend), {@link DeliveryResult#notDelivered} when certainly not
     * sent (not usable any more, Meta refused or unreachable).
     */
    DeliveryResult sendDocument(String tenantSchema, BusinessDocument document, Supplier<Pdf> pdf);

    /**
     * What the template says: « Bonjour {clientName}, voici votre {facture|devis} {reference} de {shopName}. ».
     *
     * @param to E.164 number of the client
     */
    record BusinessDocument(String to, String clientName, boolean quote, String reference, String shopName) {
    }

    /** The PDF as {@code GET /invoices/{id}/pdf} renders it. */
    record Pdf(byte[] content, String filename) {
    }
}
