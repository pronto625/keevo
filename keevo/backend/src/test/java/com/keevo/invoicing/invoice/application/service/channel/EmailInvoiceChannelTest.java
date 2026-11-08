package com.keevo.invoicing.invoice.application.service.channel;

import com.keevo.invoicing.invoice.application.usecase.InvoiceDocumentUseCase;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.TenantMessaging;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("EmailInvoiceChannel — Bref's STATUT, seller snapshot, PDF rendered after the quota probe (Story 20.8)")
class EmailInvoiceChannelTest {

    private static final Instant NOW = Instant.parse("2026-11-08T09:00:00Z");
    private static final InvoicingSettings SELLER = new InvoicingSettings("Boutique Awa SARL", "Rue Joss, Douala",
            "+237699000000", "M012345678901A", "RC/DLA/2020/B/1234", true, new BigDecimal("19.25"), true, null, null);

    private final EmailSender sender = mock(EmailSender.class);
    private final TenantMessaging messaging = mock(TenantMessaging.class);
    private final InvoiceDocumentUseCase documents = mock(InvoiceDocumentUseCase.class);
    private final InvoicePaymentRepository payments = mock(InvoicePaymentRepository.class);
    private final EmailInvoiceChannel channel = new EmailInvoiceChannel(sender, messaging, documents, payments);

    private static Invoice issued() {
        return Invoice.draft(UUID.randomUUID(), new ClientInfo(null, "Société Ndolo", null, null, null, null,
                        "compta@ndolo.cm", null, null),
                        List.of(new LineInput("Riz", BigDecimal.ONE, 5_000, VatTreatment.STANDARD)), SELLER,
                        UUID.randomUUID(), NOW)
                .issue("FAC-2026-000001", LocalDate.of(2026, 11, 8), SELLER, NOW);
    }

    @Test
    @DisplayName("STATUT: issued « En attente de paiement », paid « Payée ✓ », partly paid, overdue")
    void statusLabels() {
        Invoice invoice = issued();
        when(payments.storedState(invoice.id())).thenReturn(null, PaymentState.UNPAID, PaymentState.PAID,
                PaymentState.PARTIAL, PaymentState.OVERDUE);
        assertThat(channel.statusLabel(invoice)).isEqualTo("En attente de paiement");
        assertThat(channel.statusLabel(invoice)).isEqualTo("En attente de paiement");
        assertThat(channel.statusLabel(invoice)).isEqualTo("Payée ✓");
        assertThat(channel.statusLabel(invoice)).isEqualTo("Partiellement payée");
        assertThat(channel.statusLabel(invoice)).isEqualTo("En retard");
    }

    @Test
    @DisplayName("the seller snapshot header (name, address, phone) and the PDF built after the quota probe")
    @SuppressWarnings("unchecked")
    void sellerSnapshotAndPdfAfterProbe() {
        Invoice invoice = issued();
        when(documents.pdf(invoice.id(), "kv_test01"))
                .thenReturn(new InvoiceDocumentUseCase.InvoiceDocument("%PDF-1.7".getBytes(), "FAC-2026-000001.pdf"));
        when(messaging.sendEmail(eq("kv_test01"), any(Supplier.class))).thenAnswer(i -> {
            EmailMessage m = ((Supplier<EmailMessage>) i.getArgument(1)).get();
            assertThat(m.html()).contains("Boutique Awa SARL", "Rue Joss, Douala", "+237699000000",
                    "En attente de paiement");
            assertThat(m.attachments()).singleElement()
                    .satisfies(a -> assertThat(a.filename()).isEqualTo("FAC-2026-000001.pdf"));
            return DeliveryResult.deliveredBy("fake");
        });

        channel.deliver(new InvoiceSendChannel.Message(invoice, "FAC-2026-000001", invoice.seller().legalName(),
                invoice.seller().address(), invoice.seller().phone(), "https://k/l", 30, "compta@ndolo.cm",
                "kv_test01"));

        verify(documents).pdf(invoice.id(), "kv_test01");
    }

    @Test
    @DisplayName("quota refused at the probe → no PDF rendered; uncertain → INVOICE_SEND_UNCERTAIN; failed → 502 code")
    @SuppressWarnings("unchecked")
    void outcomes() {
        Invoice invoice = issued();
        InvoiceSendChannel.Message m = new InvoiceSendChannel.Message(invoice, "FAC-2026-000001", "Boutique", null,
                null, "https://k/l", 30, "compta@ndolo.cm", "kv_test01");
        when(messaging.sendEmail(eq("kv_test01"), any(Supplier.class)))
                .thenThrow(new DomainException(ErrorCode.QUOTA_EXCEEDED, "q"))
                .thenReturn(DeliveryResult.uncertain("resend"))
                .thenReturn(DeliveryResult.notDelivered());
        assertThat(catchThrowableOfType(DomainException.class, () -> channel.deliver(m)).getErrorCode())
                .isEqualTo(ErrorCode.QUOTA_EXCEEDED);
        verify(documents, never()).pdf(any(), any());
        assertThat(catchThrowableOfType(DomainException.class, () -> channel.deliver(m)).getErrorCode())
                .isEqualTo(ErrorCode.INVOICE_SEND_UNCERTAIN);
        assertThat(catchThrowableOfType(DomainException.class, () -> channel.deliver(m)).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_DELIVERY_FAILED);
    }
}
