package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.application.service.InvoiceSendRateLimiter;
import com.keevo.invoicing.invoice.application.service.channel.InvoiceSendChannel;
import com.keevo.invoicing.invoice.application.service.channel.SmsInvoiceChannel;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicePresentation;
import com.keevo.invoicing.invoice.domain.model.InvoiceSendRequest;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.SendChannel;
import com.keevo.invoicing.invoice.domain.model.SendStatus;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePresentationRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSendRequestRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.capability.notification.SmsSender;
import com.keevo.shared.capability.notification.TenantMessaging;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.invoicing.InvoiceSentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SendInvoiceUseCase — channel strategies, send outside any transaction, record after delivery, outbox "
        + "(Story 20.8)")
class SendInvoiceUseCaseTest {

    private static final String TENANT = "kv_test01";
    private static final String BASE = "https://keevo.test";
    private static final Instant NOW = Instant.parse("2026-11-08T09:00:00Z");

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final InvoiceSendRequestRepository requests = mock(InvoiceSendRequestRepository.class);
    private final InvoicePublicLinkUseCase links = mock(InvoicePublicLinkUseCase.class);
    private final InvoiceSendRateLimiter rateLimiter = mock(InvoiceSendRateLimiter.class);
    private final CompanyIdentity company = mock(CompanyIdentity.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final InvoicingSettingsRepository settings = mock(InvoicingSettingsRepository.class);
    private final InvoicePresentationRepository presentations = mock(InvoicePresentationRepository.class);
    private final SmsSender smsSender = mock(SmsSender.class);
    private final TenantMessaging messaging = mock(TenantMessaging.class);
    private final InvoiceSendChannel whatsApp = stub(SendChannel.WHATSAPP);
    private final InvoiceSendChannel email = stub(SendChannel.EMAIL);
    private SendInvoiceUseCase useCase;
    private Invoice invoice;
    private InvoicingSettings seller;

    private static InvoiceSendChannel stub(SendChannel code) {
        InvoiceSendChannel channel = mock(InvoiceSendChannel.class);
        when(channel.channel()).thenReturn(code);
        return channel;
    }

    private SendInvoiceUseCase useCase(List<InvoiceSendChannel> channels) {
        return new SendInvoiceUseCase(invoices, requests, channels, links, rateLimiter, company, events, txManager,
                Clock.fixed(NOW, ZoneOffset.UTC), settings, presentations);
    }

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        useCase = useCase(List.of(whatsApp, new SmsInvoiceChannel(smsSender, messaging), email));
        seller = new InvoicingSettings("Boutique Awa SARL", "Akwa, Douala", "+237690000000",
                "M012345678901A", "RC/DLA/2020/B/1234", true, new BigDecimal("19.25"), true, null, null);
        invoice = Invoice.draft(UUID.randomUUID(), new ClientInfo(null, "Société Ndolo", null, null, "677000111"),
                        List.of(new LineInput("Riz", BigDecimal.ONE, 5_000, VatTreatment.STANDARD)), seller,
                        UUID.randomUUID(), NOW)
                .issue("FAC-2026-000001", java.time.LocalDate.of(2026, 11, 8), seller, NOW);
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
        when(presentations.find(invoice.id())).thenReturn(Optional.of(InvoicePresentation.NONE));
        when(links.signedLink(eq(invoice.id()), anyInt(), anyString(), eq(TENANT)))
                .thenReturn(new InvoicePublicLinkUseCase.Link(BASE + "/api/v1/invoices/shared?token=t", NOW, 30));
        when(company.displayName(TENANT)).thenReturn("Boutique Awa");
        when(smsSender.isEnabled()).thenReturn(true);
    }

    private SendInvoiceUseCase.SendRequest sms() {
        return new SendInvoiceUseCase.SendRequest(SendChannel.SMS, null, null);
    }

    @Test
    @DisplayName("exactly one strategy per channel, else the context fails at startup")
    void registry() {
        assertThat(catchThrowableOfType(IllegalStateException.class, () -> useCase(List.of(whatsApp, email))))
                .hasMessageContaining("every channel");
        assertThat(catchThrowableOfType(IllegalStateException.class,
                () -> useCase(List.of(whatsApp, email, stub(SendChannel.SMS), stub(SendChannel.SMS)))))
                .hasMessageContaining("Two senders");
    }

    @Test
    @DisplayName("SMS delivered: any caller transaction suspended (NOT_SUPPORTED), recorded with channel + recipient, "
            + "audited event with the channel and masked number, attempt logged SENT")
    void delivered() {
        when(messaging.sendSms(eq(TENANT), any())).thenReturn(DeliveryResult.deliveredBy("fake"));

        Invoice sent = useCase.send(invoice.id(), sms(), BASE, UUID.randomUUID(), TENANT);

        verify(txManager).getTransaction(argThat(d -> d != null
                && d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED));
        ArgumentCaptor<SmsMessage> message = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messaging).sendSms(eq(TENANT), message.capture());
        assertThat(message.getValue().to()).isEqualTo("+237677000111");
        assertThat(message.getValue().text()).contains("FAC-2026-000001", BASE + "/api/v1/invoices/shared?token=t");
        assertThat(sent.delivery().channel()).isEqualTo(SendChannel.SMS);
        assertThat(sent.delivery().lastSentTo()).isEqualTo("+237677000111");
        verify(invoices).markSent(any());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(InvoiceSentEvent.class, e -> {
            assertThat(e.channel()).isEqualTo("SMS");
            assertThat(e.maskedPhone()).isEqualTo("+237 6•• ••• 111");
        });
        verify(requests).insert(argThat(r -> r.status() == SendStatus.SENT && r.channel() == SendChannel.SMS
                && r.operationId() == null));
    }

    @Test
    @DisplayName("Recording fails after delivery → still SENT (INVOICE_SENT_UNRECORDED logged), never an error")
    void recordingFailureAfterDelivery() {
        when(messaging.sendSms(eq(TENANT), any())).thenReturn(DeliveryResult.deliveredBy("fake"));
        doThrow(new IllegalStateException("db down")).when(invoices).markSent(any());

        Invoice sent = useCase.send(invoice.id(), sms(), BASE, UUID.randomUUID(), TENANT);

        assertThat(sent.delivery().channel()).isEqualTo(SendChannel.SMS);
        verify(requests).insert(argThat(r -> r.status() == SendStatus.SENT));
        verify(rateLimiter, never()).release(any());
    }

    @Test
    @DisplayName("Not delivered → SMS_DELIVERY_FAILED, nothing recorded, no event, slot back, attempt logged FAILED")
    void notDelivered() {
        when(messaging.sendSms(eq(TENANT), any())).thenReturn(DeliveryResult.notDelivered());

        DomainException e = catchThrowableOfType(DomainException.class,
                () -> useCase.send(invoice.id(), sms(), BASE, UUID.randomUUID(), TENANT));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SMS_DELIVERY_FAILED);
        verify(invoices, never()).markSent(any());
        verify(events, never()).publishEvent(any());
        verify(rateLimiter).release(TENANT);
        verify(requests).insert(argThat(r -> r.status() == SendStatus.FAILED
                && "SMS_DELIVERY_FAILED".equals(r.lastErrorCode())));
    }

    @Test
    @DisplayName("Uncertain outcome → INVOICE_SEND_UNCERTAIN (UNKNOWN, not retryable), slot kept, nothing recorded")
    void uncertain() {
        when(messaging.sendSms(eq(TENANT), any())).thenReturn(DeliveryResult.uncertain("mboasms"));

        SendInvoiceUseCase.ChannelResult result = useCase.sendAll(invoice.id(), sms(), BASE, null, TENANT)
                .results().get(0);

        assertThat(result.status()).isEqualTo(SendStatus.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("INVOICE_SEND_UNCERTAIN");
        assertThat(result.retryable()).isFalse();
        verify(rateLimiter, never()).release(any());
        verify(invoices, never()).markSent(any());
    }

    @Test
    @DisplayName("Quota exhausted (before sending) → FAILED QUOTA_EXCEEDED, not retryable, slot back")
    void quotaExceeded() {
        when(messaging.sendSms(eq(TENANT), any())).thenThrow(new DomainException(ErrorCode.QUOTA_EXCEEDED, "quota"));

        SendInvoiceUseCase.ChannelResult result = useCase.sendAll(invoice.id(), sms(), BASE, null, TENANT)
                .results().get(0);

        assertThat(result.status()).isEqualTo(SendStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(result.retryable()).isFalse();
        verify(rateLimiter).release(TENANT);
    }

    @Test
    @DisplayName("A programming error of the messaging port propagates (not mapped to a delivery failure)")
    void programmingErrorPropagates() {
        when(messaging.sendSms(eq(TENANT), any())).thenThrow(new IllegalStateException("inside a transaction"));

        assertThat(catchThrowableOfType(IllegalStateException.class,
                () -> useCase.send(invoice.id(), sms(), BASE, null, TENANT))).hasMessageContaining("transaction");
        verify(rateLimiter).release(TENANT);
    }

    @Test
    @DisplayName("No absolute public base → PUBLIC_BASE_URL_MISSING before any send (never a relative link)")
    void relativeBase() {
        for (String base : new String[]{"", null, "/api"}) {
            SendInvoiceUseCase.ChannelResult result = useCase.sendAll(invoice.id(), sms(), base, null, TENANT)
                    .results().get(0);
            assertThat(result.errorCode()).isEqualTo("PUBLIC_BASE_URL_MISSING");
        }
        verify(messaging, never()).sendSms(any(), any());
        verify(rateLimiter, never()).acquire(any());
    }

    @Test
    @DisplayName("Channel off → SMS_UNAVAILABLE before the rate limit and any send; retryable from the outbox")
    void unavailable() {
        when(smsSender.isEnabled()).thenReturn(false);

        SendInvoiceUseCase.ChannelResult result = useCase.sendAll(invoice.id(), sms(), BASE, null, TENANT)
                .results().get(0);

        assertThat(result.errorCode()).isEqualTo("SMS_UNAVAILABLE");
        assertThat(result.retryable()).isTrue();
        verify(rateLimiter, never()).acquire(any());
        verify(messaging, never()).sendSms(any(), any());
    }

    @Test
    @DisplayName("Several channels: each independently (WhatsApp failing does not stop SMS), one log row each")
    void severalChannels() {
        when(whatsApp.recipient(any(), any())).thenThrow(new DomainException(ErrorCode.INVOICE_PHONE_REQUIRED, "x"));
        when(messaging.sendSms(eq(TENANT), any())).thenReturn(DeliveryResult.deliveredBy("fake"));

        SendInvoiceUseCase.Results results = useCase.sendAll(invoice.id(), new SendInvoiceUseCase.SendRequest(
                List.of(SendChannel.WHATSAPP, SendChannel.SMS, SendChannel.WHATSAPP), null, null), BASE, null, TENANT);

        assertThat(results.results()).extracting(SendInvoiceUseCase.ChannelResult::channel)
                .containsExactly(SendChannel.WHATSAPP, SendChannel.SMS);
        assertThat(results.results()).extracting(SendInvoiceUseCase.ChannelResult::status)
                .containsExactly(SendStatus.FAILED, SendStatus.SENT);
        assertThat(results.invoice().delivery().channel()).isEqualTo(SendChannel.SMS);
        verify(requests, org.mockito.Mockito.times(2)).insert(any());
    }

    @Test
    @DisplayName("enqueue (sync): validates every channel, queues one PENDING row per channel keyed on the operation, "
            + "marks the document PENDING, sends nothing; queued again → nothing inserted")
    void enqueue() {
        when(requests.insertIfAbsent(any())).thenReturn(true, true, false, false);
        SendInvoiceUseCase.SendRequest both = new SendInvoiceUseCase.SendRequest(
                List.of(SendChannel.SMS, SendChannel.EMAIL), null, "compta@ndolo.cm");

        SendInvoiceUseCase.Queued first = useCase.enqueue(invoice.id(), both, "op-1", null, TENANT);
        SendInvoiceUseCase.Queued again = useCase.enqueue(invoice.id(), both, "op-1", null, TENANT);

        assertThat(first.inserted()).extracting(InvoiceSendRequest::channel)
                .containsExactly(SendChannel.SMS, SendChannel.EMAIL);
        assertThat(first.inserted()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(SendStatus.PENDING);
            assertThat(r.operationId()).isEqualTo("op-1");
        });
        assertThat(first.inserted().get(1).recipientOverride()).isEqualTo("compta@ndolo.cm");
        assertThat(again.inserted()).isEmpty();
        verify(invoices).markSendStatus(eq(invoice.id()), eq(SendStatus.PENDING), eq(SendChannel.EMAIL), any(),
                eq(NOW));
        verify(email, org.mockito.Mockito.times(2)).recipient(any(), eq("compta@ndolo.cm"));
        verify(messaging, never()).sendSms(any(), any());
        verify(rateLimiter, never()).acquire(any());
    }

    @Test
    @DisplayName("enqueue: a refused recipient rejects the operation, nothing queued")
    void enqueueRefused() {
        Invoice noPhone = Invoice.draft(UUID.randomUUID(), new ClientInfo(null, "Sans numéro", null, null, null),
                        List.of(new LineInput("Riz", BigDecimal.ONE, 5_000, VatTreatment.STANDARD)), seller,
                        UUID.randomUUID(), NOW)
                .issue("FAC-2026-000002", java.time.LocalDate.of(2026, 11, 8), seller, NOW);
        when(invoices.findById(noPhone.id())).thenReturn(Optional.of(noPhone));

        DomainException e = catchThrowableOfType(DomainException.class, () -> useCase.enqueue(noPhone.id(), sms(),
                "op-2", null, TENANT));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVOICE_SMS_PHONE_REQUIRED);
        verify(requests, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("SendRequest: null / empty channels = WhatsApp, duplicates removed, override per channel")
    void requests() {
        assertThat(new SendInvoiceUseCase.SendRequest((SendChannel) null, null, null).channels())
                .containsExactly(SendChannel.WHATSAPP);
        assertThat(new SendInvoiceUseCase.SendRequest(List.of(), null, null).channels())
                .containsExactly(SendChannel.WHATSAPP);
        SendInvoiceUseCase.SendRequest r = new SendInvoiceUseCase.SendRequest(
                List.of(SendChannel.EMAIL, SendChannel.SMS, SendChannel.EMAIL), "690", "a@b.cm");
        assertThat(r.channels()).containsExactly(SendChannel.EMAIL, SendChannel.SMS);
        assertThat(r.overrideFor(SendChannel.EMAIL)).isEqualTo("a@b.cm");
        assertThat(r.overrideFor(SendChannel.SMS)).isEqualTo("690");
    }
}
