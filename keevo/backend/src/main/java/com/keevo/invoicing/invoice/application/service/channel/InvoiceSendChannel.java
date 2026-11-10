package com.keevo.invoicing.invoice.application.service.channel;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.SendChannel;

/**
 * Story 20.8 (FUS-FR22) — one way of sending a document to its client (Strategy, one per {@link SendChannel}, chosen
 * in {@code SendInvoiceUseCase}): WhatsApp (14.2), SMS, email. The use case keeps the common steps (sendable check,
 * rate limit, public link, recording, audit, outbox); a channel only knows its recipient, its availability and its
 * message.
 */
public interface InvoiceSendChannel {

    SendChannel channel();

    /**
     * The recipient, normalised (E.164 number or email address): {@code override} typed by the user (the number for
     * WhatsApp / SMS, the address for email), else the client's.
     *
     * @throws com.keevo.shared.domain.exception.DomainException 422 when missing or malformed (French, per channel)
     */
    String recipient(Invoice invoice, String override);

    /** @throws com.keevo.shared.domain.exception.DomainException 503 when no provider is configured */
    void assertAvailable();

    /**
     * Story 21.5 — {@link #assertAvailable()} for a company: a channel whose availability depends on the company (the
     * WhatsApp of the owner's own number) overrides it.
     */
    default void assertAvailable(String tenantSchema) {
        assertAvailable();
    }

    /**
     * Sends the message. Called with no transaction open (SMS / email are counted on the tenant quota only after the
     * provider accepted them). Only provider outcomes are mapped; a programming error propagates as is.
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code QUOTA_EXCEEDED} before sending, the channel's
     *         delivery failure (502, certainly not delivered) or {@code INVOICE_SEND_UNCERTAIN} (maybe delivered)
     */
    void deliver(Message message);

    /**
     * What to send.
     *
     * @param reference   printed reference ({@code FAC-…}, {@code DV-…}, {@code BRF-…})
     * @param sellerName  shop name as printed on the PDF
     * @param link        signed public link (absolute)
     * @param linkDays    lifetime of the link, in days
     * @param to          normalised recipient ({@link #recipient})
     */
    record Message(Invoice invoice, String reference, String sellerName, String sellerAddress, String sellerPhone,
                   String link, int linkDays, String to, String tenantSchema) {
    }
}
