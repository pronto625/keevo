package com.keevo.invoicing.invoice.application.service;

import com.keevo.invoicing.invoice.application.usecase.SendInvoiceUseCase;
import com.keevo.invoicing.invoice.application.usecase.SendInvoiceUseCase.ChannelResult;
import com.keevo.invoicing.invoice.domain.model.InvoiceSendRequest;
import com.keevo.invoicing.invoice.domain.model.SendStatus;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSendRequestRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Story 20.8 — the durable send outbox ({@code invoice_send_requests}), Bref's {@code InvoiceOutboxProcessor}: a queued
 * offline sending ({@code SEND_INVOICE}) leaves after the push committed, never inside it.
 * <ul>
 *   <li><b>after commit</b> ({@link #dispatchAfterCommit}): the requests just queued are handed to a small bounded
 *       worker pool; a full pool drops the task — the poller picks the rows up;</li>
 *   <li><b>poller</b> ({@link #pollTenant}, driven by the ShedLock job every 30 s): stale SENDING rows (worker gone
 *       mid-send) end UNKNOWN, then up to {@link #BATCH} due PENDING rows are claimed in ONE statement;</li>
 *   <li><b>one send per request</b>: a worker only sends a row it claimed with the transition
 *       {@code PENDING → SENDING} (single conditional UPDATE), and a request exists once per operation and channel
 *       (unique key);</li>
 *   <li><b>outcome</b>: SENT; UNKNOWN (maybe delivered: never retried, not counted); a retryable failure (provider,
 *       channel off, rate limit, unexpected error) back to PENDING with backoff 30 s × 2^(n−1) until
 *       {@link InvoiceSendRequest#MAX_ATTEMPTS} (Bref: 5); any other refusal ({@code QUOTA_EXCEEDED}, missing
 *       recipient, document no longer sendable, {@code PUBLIC_BASE_URL_MISSING}) FAILED at once. A final outcome is
 *       copied on the document ({@code last_send_status}) so the app sees it in the pull.</li>
 * </ul>
 * The provider call runs with no transaction; each row update is its own short transaction. Cost per tick: 1
 * catalogue query, then per tenant 1 lease UPDATE + 1 claim UPDATE (set-based) + per claimed row the send and 1–2
 * UPDATEs by PK.
 */
@Service
public class InvoiceSendOutbox {

    private static final Logger log = LoggerFactory.getLogger(InvoiceSendOutbox.class);

    /** Rows claimed per tenant per tick. */
    static final int BATCH = 20;
    /** A SENDING row older than this was abandoned mid-send. */
    static final Duration LEASE = Duration.ofMinutes(10);

    private final SendInvoiceUseCase sending;
    private final InvoiceSendRequestRepository requests;
    private final InvoiceRepository invoices;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String publicBaseUrl;
    private final ExecutorService workers;

    public InvoiceSendOutbox(SendInvoiceUseCase sending, InvoiceSendRequestRepository requests,
                             InvoiceRepository invoices, PlatformTransactionManager transactionManager, Clock clock,
                             @Value("${keevo.public-base-url:}") String publicBaseUrl) {
        this.sending = sending;
        this.requests = requests;
        this.invoices = invoices;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.publicBaseUrl = com.keevo.invoicing.invoice.application.usecase.InvoicePublicLinkUseCase
                .stripTrailingSlash(publicBaseUrl == null ? "" : publicBaseUrl.strip());
        AtomicInteger n = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200), r -> {
            Thread t = new Thread(r, "invoice-send-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.DiscardPolicy()); // dropped → the poller sends it
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }

    /**
     * After the current transaction commits (immediately when none is active), sends the requests {@code ids} of
     * {@code tenantSchema} on a worker thread. A rolled-back push sends nothing (its rows are gone too).
     */
    public void dispatchAfterCommit(String tenantSchema, List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Runnable task = () -> workers.execute(() -> processIds(tenantSchema, ids));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /** Claims and sends each of {@code ids} (one already taken by the poller is skipped). */
    public void processIds(String tenantSchema, List<UUID> ids) {
        try {
            TenantContext.setCurrentTenant(tenantSchema);
            for (UUID id : ids) {
                requests.claim(id, clock.instant()).ifPresent(r -> process(r, tenantSchema));
            }
        } catch (RuntimeException e) {
            log.warn("INVOICE_SEND_DISPATCH_FAILED tenant={} error={} — left to the poller", tenantSchema,
                    e.getClass().getSimpleName());
        } finally {
            TenantContext.clear();
        }
    }

    /** One tenant's tick (tenant bound by the caller). @return requests processed */
    public int pollTenant(String tenantSchema) {
        Instant now = clock.instant();
        for (InvoiceSendRequest stale : requests.expireStale(now.minus(LEASE), now)) {
            log.warn("INVOICE_SEND_INTERRUPTED channel={} tenant={} — marked uncertain, not retried",
                    stale.channel(), tenantSchema);
            markDocument(stale, SendStatus.UNKNOWN, ErrorCode.INVOICE_SEND_UNCERTAIN.name());
        }
        List<InvoiceSendRequest> due = requests.claimDue(now, BATCH);
        for (InvoiceSendRequest request : due) {
            process(request, tenantSchema);
        }
        return due.size();
    }

    /** A claimed (SENDING) request: deliver, then end it. */
    void process(InvoiceSendRequest request, String tenantSchema) {
        ChannelResult result;
        try {
            result = sending.deliverQueued(request, publicBaseUrl, tenantSchema);
        } catch (RuntimeException e) {
            log.error("INVOICE_SEND_ERROR channel={} tenant={} error={}", request.channel(), tenantSchema,
                    e.getClass().getSimpleName(), e);
            result = new ChannelResult(request.channel(), SendStatus.FAILED,
                    new DomainException(ErrorCode.INTERNAL_ERROR, e), null);
        }
        Instant now = clock.instant();
        SendStatus end;
        Instant next = null;
        if (result.status() == SendStatus.SENT || result.status() == SendStatus.UNKNOWN) {
            end = result.status();
        } else if (result.retryable() && request.attempts() < InvoiceSendRequest.MAX_ATTEMPTS) {
            end = SendStatus.PENDING;
            next = now.plus(InvoiceSendRequest.backoff(request.attempts()));
        } else {
            end = SendStatus.FAILED;
        }
        SendStatus finalStatus = end;
        Instant nextAttempt = next;
        String errorCode = result.errorCode();
        try {
            tx.executeWithoutResult(s -> requests.complete(request.id(), finalStatus, errorCode, nextAttempt, now));
            if (finalStatus == SendStatus.FAILED || finalStatus == SendStatus.UNKNOWN) {
                markDocument(request, finalStatus, errorCode);
            }
        } catch (RuntimeException e) {
            // a SENT row left SENDING ends UNKNOWN at its lease: never sent twice
            log.error("INVOICE_SEND_COMPLETE_FAILED channel={} status={} tenant={} error={}", request.channel(),
                    finalStatus, tenantSchema, e.getClass().getSimpleName());
        }
        if (finalStatus != SendStatus.SENT) {
            log.info("INVOICE_SEND_QUEUED_RESULT channel={} status={} attempts={} code={} tenant={}",
                    request.channel(), finalStatus, request.attempts(), errorCode, tenantSchema);
        }
    }

    private void markDocument(InvoiceSendRequest request, SendStatus status, String errorCode) {
        tx.executeWithoutResult(s -> invoices.markSendStatus(request.invoiceId(), status, request.channel(), errorCode,
                clock.instant()));
    }
}
