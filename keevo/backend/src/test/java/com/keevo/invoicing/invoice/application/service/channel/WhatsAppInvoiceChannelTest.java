package com.keevo.invoicing.invoice.application.service.channel;

import com.keevo.invoicing.invoice.application.usecase.InvoiceDocumentUseCase;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.TenantWhatsAppBusiness;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WhatsAppInvoiceChannel — the owner's WhatsApp Business first, the platform text as fallback (21.5)")
class WhatsAppInvoiceChannelTest {

    private WhatsAppSender platform;
    private TenantWhatsAppBusiness business;
    private InvoiceDocumentUseCase documents;
    private WhatsAppInvoiceChannel channel;
    private InvoiceSendChannel.Message message;

    @BeforeEach
    void setUp() {
        platform = com.keevo.shared.capability.notification.WhatsAppSenderMocks.forwardNotifications(mock(WhatsAppSender.class));
        business = mock(TenantWhatsAppBusiness.class);
        documents = mock(InvoiceDocumentUseCase.class);
        channel = new WhatsAppInvoiceChannel(platform, business, documents);
        Invoice invoice = mock(Invoice.class, Answers.RETURNS_DEEP_STUBS);
        when(invoice.client().name()).thenReturn("Awa");
        when(invoice.isQuote()).thenReturn(false);
        when(invoice.totals().totalTtc()).thenReturn(28_850L);
        message = new InvoiceSendChannel.Message(invoice, "FAC-2026-000001", "Boutique Awa", null, null,
                "https://keevo.ad2s.cm/l", 30, "+237690112233", "kv_abc123");
    }

    @Test
    @DisplayName("usable WABA: sent from the owner's number, the platform is not called")
    void business() {
        when(business.canSendDocuments("kv_abc123")).thenReturn(true);
        when(business.sendDocument(eq("kv_abc123"), any(), any())).thenReturn(DeliveryResult.deliveredBy("whatsapp-cloud"));
        channel.deliver(message);
        verify(platform, never()).sendReport(anyString(), anyString());
    }

    @Test
    @DisplayName("WABA uncertain: INVOICE_SEND_UNCERTAIN, never the platform text too")
    void uncertain() {
        when(business.canSendDocuments("kv_abc123")).thenReturn(true);
        when(business.sendDocument(eq("kv_abc123"), any(), any())).thenReturn(DeliveryResult.uncertain("whatsapp-cloud"));
        assertThatThrownBy(() -> channel.deliver(message)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVOICE_SEND_UNCERTAIN);
        verify(platform, never()).sendReport(anyString(), anyString());
    }

    @Test
    @DisplayName("WABA refused: platform free text with the link; without a platform provider → 502")
    @SuppressWarnings("unchecked")
    void refusedFallsBack() {
        when(business.canSendDocuments("kv_abc123")).thenReturn(true);
        when(business.sendDocument(eq("kv_abc123"), any(), any(Supplier.class))).thenReturn(DeliveryResult.notDelivered());
        when(platform.isConfigured()).thenReturn(true);
        channel.deliver(message);
        verify(platform).sendReport(eq("+237690112233"), org.mockito.ArgumentMatchers.contains("https://keevo.ad2s.cm/l"));
        verify(platform).sendNotification(eq("+237690112233"), org.mockito.ArgumentMatchers.argThat(m -> m.kind() == com.keevo.shared.capability.notification.PlatformMessageKind.DOCUMENT_LINK && m.parameters().equals(java.util.List.of("Awa", "facture", "FAC-2026-000001", "Boutique Awa",
                com.keevo.invoicing.invoice.application.service.InvoiceSendMessages.amount(28_850L),
                "https://keevo.ad2s.cm/l"))));

        when(platform.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> channel.deliver(message)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.WHATSAPP_DELIVERY_FAILED);
    }

    @Test
    @DisplayName("availability: the platform or the company's WABA")
    void availability() {
        when(platform.isConfigured()).thenReturn(false);
        when(business.canSendDocuments("kv_abc123")).thenReturn(true);
        channel.assertAvailable("kv_abc123");
        when(business.canSendDocuments("kv_abc123")).thenReturn(false);
        assertThatThrownBy(() -> channel.assertAvailable("kv_abc123")).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.WHATSAPP_UNAVAILABLE);
        assertThat(channel.channel().name()).isEqualTo("WHATSAPP");
    }

    @Test
    @DisplayName("19.9c a quote names « devis » in the template parameters")
    void quoteParameters() {
        Invoice quote = mock(Invoice.class, Answers.RETURNS_DEEP_STUBS);
        when(quote.client().name()).thenReturn("Awa");
        when(quote.isQuote()).thenReturn(true);
        when(quote.totals().totalTtc()).thenReturn(1_000L);
        assertThat(WhatsAppInvoiceChannel.message(quote, "DV-1", "Boutique Awa", "https://k.cm/l").parameters()
                .subList(0, 4)).containsExactly("Awa", "devis", "DV-1", "Boutique Awa");
    }
}
