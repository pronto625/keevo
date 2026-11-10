package com.keevo.invoicing.invoice.application.service.channel;

import com.keevo.invoicing.invoice.application.service.InvoicePhones;
import com.keevo.invoicing.invoice.application.service.InvoiceSendMessages;
import com.keevo.invoicing.invoice.application.usecase.InvoiceDocumentUseCase;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.SendChannel;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.TenantWhatsAppBusiness;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Story 14.2 / 20.7 — WhatsApp sending through the core {@link WhatsAppSender} (text with the public link), unchanged
 * by 20.8: same codes ({@code INVOICE_PHONE_REQUIRED/INVALID} 422, {@code WHATSAPP_UNAVAILABLE} 503,
 * {@code WHATSAPP_DELIVERY_FAILED} 502), no tenant quota. The WhatsApp port signals a provider failure by throwing
 * (no outcome value): any exception of {@code sendReport} is that provider failure, as in 14.2.
 *
 * <p>Story 21.5 (routed from the 19.9b review) — when the company's own WhatsApp Business can send documents
 * ({@link TenantWhatsAppBusiness#canSendDocuments}: module enabled, number connected, template {@code bref_facture}
 * approved), the document leaves from the owner's number with that template and the PDF (Bref's rule: never from the
 * platform number). Otherwise, or when Meta certainly refused it, the platform free text with the link as before; an
 * unknown outcome of Meta (timeout) is {@code INVOICE_SEND_UNCERTAIN}, never followed by the platform text (no double
 * sending). Available when either path is.
 */
@Component
public class WhatsAppInvoiceChannel implements InvoiceSendChannel {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppInvoiceChannel.class);

    private final WhatsAppSender whatsApp;
    private final TenantWhatsAppBusiness business;
    private final InvoiceDocumentUseCase documents;

    public WhatsAppInvoiceChannel(WhatsAppSender whatsApp, TenantWhatsAppBusiness business,
                                  InvoiceDocumentUseCase documents) {
        this.whatsApp = whatsApp;
        this.business = business;
        this.documents = documents;
    }

    @Override
    public SendChannel channel() {
        return SendChannel.WHATSAPP;
    }

    @Override
    public String recipient(Invoice invoice, String override) {
        return InvoicePhones.normalize(override != null && !override.isBlank() ? override : invoice.client().phone());
    }

    /** The platform sender alone (no company known). */
    @Override
    public void assertAvailable() {
        if (!whatsApp.isConfigured()) {
            throw new DomainException(ErrorCode.WHATSAPP_UNAVAILABLE, "No WhatsApp provider configured");
        }
    }

    /** Story 21.5 — the platform sender or the company's own WhatsApp Business. */
    @Override
    public void assertAvailable(String tenantSchema) {
        if (!whatsApp.isConfigured() && !business.canSendDocuments(tenantSchema)) {
            throw new DomainException(ErrorCode.WHATSAPP_UNAVAILABLE, "No WhatsApp provider configured");
        }
    }

    @Override
    public void deliver(Message m) {
        if (business.canSendDocuments(m.tenantSchema())) {
            Invoice invoice = m.invoice();
            DeliveryResult result = business.sendDocument(m.tenantSchema(),
                    new TenantWhatsAppBusiness.BusinessDocument(m.to(), invoice.client().name(), invoice.isQuote(),
                            m.reference(), m.sellerName()),
                    () -> {
                        InvoiceDocumentUseCase.InvoiceDocument pdf = documents.pdf(invoice.id(), m.tenantSchema());
                        return new TenantWhatsAppBusiness.Pdf(pdf.content(), pdf.filename());
                    });
            if (result.delivered()) {
                return;
            }
            if (result.uncertain()) {
                throw new DomainException(ErrorCode.INVOICE_SEND_UNCERTAIN, "WhatsApp Business outcome unknown");
            }
            if (!whatsApp.isConfigured()) {
                log.warn("INVOICE_WHATSAPP_BUSINESS_FAILED tenant={} — no platform WhatsApp to fall back on",
                        m.tenantSchema());
                throw new DomainException(ErrorCode.WHATSAPP_DELIVERY_FAILED, "WhatsApp Business refused the document");
            }
            log.warn("INVOICE_WHATSAPP_BUSINESS_FAILED tenant={} — platform WhatsApp used", m.tenantSchema());
        } else if (!whatsApp.isConfigured()) {
            // the company's number stopped being usable since the availability check, and no platform provider
            throw new DomainException(ErrorCode.WHATSAPP_UNAVAILABLE, "No WhatsApp provider configured");
        }
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
