package com.keevo.invoicing.invoice.application.service.channel;

import com.keevo.invoicing.invoice.application.service.InvoicePhones;
import com.keevo.invoicing.invoice.application.service.InvoiceSendMessages;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.SendChannel;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Story 14.2 / 20.7 — WhatsApp sending through the core {@link WhatsAppSender} (text with the public link), unchanged
 * by 20.8: same codes ({@code INVOICE_PHONE_REQUIRED/INVALID} 422, {@code WHATSAPP_UNAVAILABLE} 503,
 * {@code WHATSAPP_DELIVERY_FAILED} 502), no tenant quota. The WhatsApp port signals a provider failure by throwing
 * (no outcome value): any exception of {@code sendReport} is that provider failure, as in 14.2.
 */
@Component
public class WhatsAppInvoiceChannel implements InvoiceSendChannel {

    private final WhatsAppSender whatsApp;

    public WhatsAppInvoiceChannel(WhatsAppSender whatsApp) {
        this.whatsApp = whatsApp;
    }

    @Override
    public SendChannel channel() {
        return SendChannel.WHATSAPP;
    }

    @Override
    public String recipient(Invoice invoice, String override) {
        return InvoicePhones.normalize(override != null && !override.isBlank() ? override : invoice.client().phone());
    }

    @Override
    public void assertAvailable() {
        if (!whatsApp.isConfigured()) {
            throw new DomainException(ErrorCode.WHATSAPP_UNAVAILABLE, "No WhatsApp provider configured");
        }
    }

    @Override
    public void deliver(Message m) {
        String text = text(m.invoice(), m.reference(), m.sellerName(), m.link());
        try {
            whatsApp.sendReport(m.to(), text);
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            // the port's provider failure (14.2 contract)
            throw new DomainException(ErrorCode.WHATSAPP_DELIVERY_FAILED, e);
        }
    }

    /**
     * « Bonjour Awa, voici votre facture FAC-2026-000001 de Tech Centre d'un montant de 28 850 XAF : https://… »;
     * Story 20.7: « … voici votre devis DV-1A2B3C4D de … » for a quote.
     */
    public static String text(Invoice invoice, String reference, String displayName, String link) {
        return "Bonjour " + invoice.client().name() + ", voici votre " + (invoice.isQuote() ? "devis " : "facture ")
                + reference + " de " + displayName + " d'un montant de "
                + InvoiceSendMessages.amount(invoice.totals().totalTtc()) + " XAF : " + link;
    }
}
