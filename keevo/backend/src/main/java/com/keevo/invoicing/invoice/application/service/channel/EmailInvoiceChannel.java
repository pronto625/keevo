package com.keevo.invoicing.invoice.application.service.channel;

import com.keevo.invoicing.invoice.application.service.InvoiceRecipients;
import com.keevo.invoicing.invoice.application.service.InvoiceSendMessages;
import com.keevo.invoicing.invoice.application.usecase.InvoiceDocumentUseCase;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.model.SendChannel;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailAttachment;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.TenantMessaging;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Story 20.8 — email sending (Bref's subject, text and HTML card + the public link, the PDF attached as in Bref)
 * through {@link TenantMessaging}: quota probed first, then the PDF rendered (never for a refused email), then sent;
 * counted ({@code EMAILS}) only after a provider accepted it. The sender address is the platform's
 * ({@code keevo.email.from}); its display name is the shop name. An exception (PDF rendering, programming error)
 * propagates as is: only provider outcomes become delivery failures.
 */
@Component
public class EmailInvoiceChannel implements InvoiceSendChannel {

    private final EmailSender emailSender;
    private final TenantMessaging messaging;
    private final InvoiceDocumentUseCase documents;
    private final InvoicePaymentRepository payments;

    public EmailInvoiceChannel(EmailSender emailSender, TenantMessaging messaging, InvoiceDocumentUseCase documents,
                               InvoicePaymentRepository payments) {
        this.emailSender = emailSender;
        this.messaging = messaging;
        this.documents = documents;
        this.payments = payments;
    }

    @Override
    public SendChannel channel() {
        return SendChannel.EMAIL;
    }

    @Override
    public String recipient(Invoice invoice, String override) {
        return InvoiceRecipients.email(InvoiceRecipients.pick(override, invoice.client().email()));
    }

    @Override
    public void assertAvailable() {
        if (!emailSender.isEnabled()) {
            throw new DomainException(ErrorCode.EMAIL_UNAVAILABLE, "No email provider configured");
        }
    }

    @Override
    public void deliver(Message m) {
        Invoice invoice = m.invoice();
        InvoiceSendMessages.Email email = InvoiceSendMessages.email(new InvoiceSendMessages.EmailContent(
                invoice.isQuote(), m.reference(), invoice.client().name(), m.sellerName(), m.sellerAddress(),
                m.sellerPhone(), invoice.totals().totalTtc(), statusLabel(invoice), m.link(), m.linkDays()));
        DeliveryResult result = messaging.sendEmail(m.tenantSchema(), () -> {
            // rendered after the quota probe: the PDF exactly as GET /pdf renders it (layout, payments, balance due)
            InvoiceDocumentUseCase.InvoiceDocument pdf = documents.pdf(invoice.id(), m.tenantSchema());
            return new EmailMessage(m.to(), email.subject(), email.text(), email.html(), m.sellerName(),
                    List.of(new EmailAttachment(pdf.filename(), "application/pdf", pdf.content())));
        });
        InvoiceDeliveryOutcomes.require(result, ErrorCode.EMAIL_DELIVERY_FAILED);
    }

    /** Bref's STATUT: a quote waits for validation; an invoice by its stored payment state (one PK read). */
    String statusLabel(Invoice invoice) {
        if (invoice.isQuote()) {
            return "En attente de validation";
        }
        PaymentState state = payments.storedState(invoice.id());
        if (state == PaymentState.PAID || (state == null && invoice.status() == InvoiceStatus.PAID)) {
            return "Payée ✓";
        }
        if (state == PaymentState.PARTIAL) {
            return "Partiellement payée";
        }
        if (state == PaymentState.OVERDUE) {
            return "En retard";
        }
        return "En attente de paiement";
    }
}
