package com.keevo.invoicing.invoice.application.service;

import com.keevo.invoicing.invoice.application.usecase.SendInvoiceUseCase;
import com.keevo.invoicing.invoice.application.usecase.SendInvoiceUseCase.ChannelResult;
import com.keevo.invoicing.invoice.domain.model.InvoiceSendRequest;
import com.keevo.invoicing.invoice.domain.model.SendChannel;
import com.keevo.invoicing.invoice.domain.model.SendStatus;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSendRequestRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InvoiceSendOutbox — Bref's outbox: retries with backoff, UNKNOWN never retried, refusals final (20.8)")
class InvoiceSendOutboxTest {

    private static final String TENANT = "kv_test01";
    private static final Instant NOW = Instant.parse("2026-11-08T09:00:00Z");

    private final SendInvoiceUseCase sending = mock(SendInvoiceUseCase.class);
    private final InvoiceSendRequestRepository requests = mock(InvoiceSendRequestRepository.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private InvoiceSendOutbox outbox;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        outbox = new InvoiceSendOutbox(sending, requests, invoices, txManager, Clock.fixed(NOW, ZoneOffset.UTC),
                "https://keevo.test/");
    }

    private static InvoiceSendRequest claimed(int attempts) {
        return new InvoiceSendRequest(UUID.randomUUID(), UUID.randomUUID(), "op-1", SendChannel.SMS, null,
                SendStatus.SENDING, attempts, null, NOW, null, NOW, NOW);
    }

    private static ChannelResult failed(ErrorCode code) {
        return new ChannelResult(SendChannel.SMS, code == ErrorCode.INVOICE_SEND_UNCERTAIN ? SendStatus.UNKNOWN
                : SendStatus.FAILED, new DomainException(code, "x"), null);
    }

    @Test
    @DisplayName("SENT → row SENT (the document was recorded by the send), the configured base is used")
    void sent() {
        InvoiceSendRequest r = claimed(1);
        when(sending.deliverQueued(r, "https://keevo.test", TENANT))
                .thenReturn(new ChannelResult(SendChannel.SMS, SendStatus.SENT, null, null));

        outbox.process(r, TENANT);

        verify(requests).complete(r.id(), SendStatus.SENT, null, null, NOW);
        verify(invoices, never()).markSendStatus(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("provider failure → back to PENDING with backoff 30 s × 2^(n−1); at the 5th attempt → FAILED")
    void retryWithBackoff() {
        for (int attempts = 1; attempts <= 4; attempts++) {
            InvoiceSendRequest r = claimed(attempts);
            when(sending.deliverQueued(any(), any(), any())).thenReturn(failed(ErrorCode.SMS_DELIVERY_FAILED));
            outbox.process(r, TENANT);
            Duration expected = Duration.ofSeconds(30L << (attempts - 1));
            verify(requests).complete(r.id(), SendStatus.PENDING, "SMS_DELIVERY_FAILED", NOW.plus(expected), NOW);
        }
        InvoiceSendRequest last = claimed(5);
        outbox.process(last, TENANT);
        verify(requests).complete(last.id(), SendStatus.FAILED, "SMS_DELIVERY_FAILED", null, NOW);
        verify(invoices).markSendStatus(last.invoiceId(), SendStatus.FAILED, SendChannel.SMS, "SMS_DELIVERY_FAILED",
                NOW);
    }

    @Test
    @DisplayName("uncertain → UNKNOWN at once, never retried, copied on the document")
    void unknownNotRetried() {
        InvoiceSendRequest r = claimed(1);
        when(sending.deliverQueued(any(), any(), any())).thenReturn(failed(ErrorCode.INVOICE_SEND_UNCERTAIN));

        outbox.process(r, TENANT);

        verify(requests).complete(r.id(), SendStatus.UNKNOWN, "INVOICE_SEND_UNCERTAIN", null, NOW);
        verify(invoices).markSendStatus(r.invoiceId(), SendStatus.UNKNOWN, SendChannel.SMS, "INVOICE_SEND_UNCERTAIN",
                NOW);
    }

    @Test
    @DisplayName("refusals are final at the first attempt: quota exhausted, recipient, no public base")
    void refusalsFinal() {
        for (ErrorCode code : List.of(ErrorCode.QUOTA_EXCEEDED, ErrorCode.INVOICE_SMS_PHONE_REQUIRED,
                ErrorCode.PUBLIC_BASE_URL_MISSING, ErrorCode.INVOICE_INVALID_TRANSITION)) {
            InvoiceSendRequest r = claimed(1);
            when(sending.deliverQueued(any(), any(), any())).thenReturn(failed(code));
            outbox.process(r, TENANT);
            verify(requests).complete(r.id(), SendStatus.FAILED, code.name(), null, NOW);
        }
    }

    @Test
    @DisplayName("an unexpected error is retried as INTERNAL_ERROR (never lost, never a crash of the worker)")
    void unexpectedErrorRetried() {
        InvoiceSendRequest r = claimed(1);
        when(sending.deliverQueued(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        outbox.process(r, TENANT);

        verify(requests).complete(eq(r.id()), eq(SendStatus.PENDING), eq("INTERNAL_ERROR"), any(), eq(NOW));
    }

    @Test
    @DisplayName("poll: stale SENDING rows end UNKNOWN on their document, then one claim of the due rows")
    void poll() {
        InvoiceSendRequest stale = claimed(1);
        InvoiceSendRequest due = claimed(1);
        when(requests.expireStale(NOW.minus(InvoiceSendOutbox.LEASE), NOW)).thenReturn(List.of(stale));
        when(requests.claimDue(NOW, InvoiceSendOutbox.BATCH)).thenReturn(List.of(due));
        when(sending.deliverQueued(any(), any(), any()))
                .thenReturn(new ChannelResult(SendChannel.SMS, SendStatus.SENT, null, null));

        assertThat(outbox.pollTenant(TENANT)).isEqualTo(1);

        verify(invoices).markSendStatus(stale.invoiceId(), SendStatus.UNKNOWN, SendChannel.SMS,
                "INVOICE_SEND_UNCERTAIN", NOW);
        verify(requests).complete(due.id(), SendStatus.SENT, null, null, NOW);
    }

    @Test
    @DisplayName("processIds: a row already claimed elsewhere is skipped (one send per request)")
    void claimedElsewhere() {
        UUID id = UUID.randomUUID();
        when(requests.claim(id, NOW)).thenReturn(java.util.Optional.empty());

        outbox.processIds(TENANT, List.of(id));

        verify(sending, never()).deliverQueued(any(), any(), any());
        verify(requests, never()).complete(any(), any(), any(), isNull(), any());
    }
}
