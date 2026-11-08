package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.application.service.InvoicePhones;
import com.keevo.invoicing.invoice.application.service.InvoiceSendRateLimiter;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.invoicing.InvoiceSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Sends an invoice to its client by WhatsApp (Story 14.2, MOD-FR17): a French text message with a signed public link
 * to the PDF (valid 30 days) — through the core {@link WhatsAppSender}, text only (no media API).
 *
 * <p>Order: the invoice must be ISSUED or PAID (re-sending allowed; DRAFT / CANCELLED → 409); the number is the one
 * given, else the client's ({@code INVOICE_PHONE_REQUIRED} / {@code INVOICE_PHONE_INVALID} → 422); WhatsApp must be
 * configured ({@code WHATSAPP_UNAVAILABLE} → 503); at most 30 sendings per tenant per hour (429). The message leaves
 * <b>before</b> anything is written: a provider failure (502) records nothing and publishes nothing. Then, in one
 * transaction with the row locked, the sending is recorded and {@link InvoiceSentEvent} published (audit).
 *
 * <p>Story 20.7: a quote waiting for its validation is sent too (« voici votre devis DV-… »); the link is signed by
 * {@link InvoicePublicLinkUseCase} (the document's current link version, the tenant's lifetime — 30 days by default).
 */
@Service
public class SendInvoiceUseCase {

    /** Path of the public invoice link (JWT-free, see {@code SecurityConfig.PUBLIC_PATHS}). */
    public static final String SHARED_PATH = InvoicePublicLinkUseCase.SHARED_PATH;

    private static final Logger log = LoggerFactory.getLogger(SendInvoiceUseCase.class);

    private final InvoiceRepository invoices;
    private final WhatsAppSender whatsApp;
    private final InvoicePublicLinkUseCase links;
    private final InvoiceSendRateLimiter rateLimiter;
    private final CompanyIdentity companyIdentity;
    private final InvoicingSettingsRepository settings;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SendInvoiceUseCase(InvoiceRepository invoices, WhatsAppSender whatsApp, InvoicePublicLinkUseCase links,
                              InvoiceSendRateLimiter rateLimiter, CompanyIdentity companyIdentity,
                              ApplicationEventPublisher events, TransactionTemplate tx, Clock clock,
                              InvoicingSettingsRepository settings) {
        this.settings = settings;
        this.invoices = invoices;
        this.whatsApp = whatsApp;
        this.links = links;
        this.rateLimiter = rateLimiter;
        this.companyIdentity = companyIdentity;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param phone   WhatsApp number typed by the user, {@code null} / blank = the client's phone
     * @param baseUrl absolute base of the public link ({@code https://keevo.ad2s.cm}), without trailing slash
     * @return the invoice with its sending recorded
     */
    public Invoice send(UUID id, String phone, String baseUrl, UUID actorId, String tenantSchema) {
        Invoice invoice = invoices.findById(id).orElseThrow(InvoiceQueryUseCase::notFound);
        invoice.assertSendable();
        String to = InvoicePhones.normalize(phone != null && !phone.isBlank() ? phone : invoice.client().phone());
        if (!whatsApp.isConfigured()) {
            throw new DomainException(ErrorCode.WHATSAPP_UNAVAILABLE, "No WhatsApp provider configured");
        }
        rateLimiter.acquire(tenantSchema);

        Instant now = clock.instant();
        String link = links.signedLink(invoice.id(), baseUrl, tenantSchema).url();
        String message = message(invoice, sellerName(invoice, tenantSchema), link);
        try {
            whatsApp.sendReport(to, message);
        } catch (DomainException e) {
            rateLimiter.release(tenantSchema);
            throw e;
        } catch (RuntimeException e) {
            rateLimiter.release(tenantSchema);
            log.warn("INVOICE_SEND_FAILED number={} error={}", invoice.documentReference(), e.getClass().getSimpleName());
            throw new DomainException(ErrorCode.WHATSAPP_DELIVERY_FAILED, e);
        }

        // Delivered: always recorded (even if the invoice was cancelled meanwhile — the client has the message).
        Invoice sent = tx.execute(status -> {
            Invoice locked = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
            Invoice recorded = locked.recordSending(to, now);
            invoices.markSent(recorded);
            events.publishEvent(new InvoiceSentEvent(recorded.id(), recorded.documentReference(),
                    recorded.client().name(),
                    recorded.totals().totalTtc(), InvoicePhones.mask(to), actorId, tenantSchema, now));
            return recorded;
        });
        log.info("INVOICE_SENT number={} tenant={}", invoice.documentReference(), tenantSchema);
        return sent;
    }

    /**
     * The seller name printed on the PDF: the snapshot frozen at issue; Story 20.7 — for a quote (no snapshot) the
     * current legal name of the settings, as on its PDF; else the company display name.
     */
    private String sellerName(Invoice invoice, String tenantSchema) {
        if (invoice.seller() != null && invoice.seller().legalName() != null
                && !invoice.seller().legalName().isBlank()) {
            return invoice.seller().legalName();
        }
        if (invoice.seller() == null) {
            String legal = settings.find().map(s -> s.legalName()).orElse(null);
            if (legal != null && !legal.isBlank()) {
                return legal;
            }
        }
        return companyIdentity.displayName(tenantSchema);
    }

    /**
     * « Bonjour Awa, voici votre facture FAC-2026-000001 de Tech Centre d'un montant de 28 850 XAF : https://… »;
     * Story 20.7: « … voici votre devis DV-1A2B3C4D de … » for a quote.
     */
    static String message(Invoice invoice, String displayName, String link) {
        return "Bonjour " + invoice.client().name() + ", voici votre " + (invoice.isQuote() ? "devis " : "facture ")
                + invoice.documentReference() + " de " + displayName + " d'un montant de " + amount(invoice.totals().totalTtc()) + " XAF : " + link;
    }

    /** {@code 28850} → {@code 28 850} (plain spaces). */
    static String amount(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder sb = new StringBuilder();
        int lead = digits.length() % 3;
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (i - lead) % 3 == 0) sb.append(' ');
            sb.append(digits.charAt(i));
        }
        return value < 0 ? "-" + sb : sb.toString();
    }

}
