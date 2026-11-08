package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.InvoiceSendRequest;
import com.keevo.invoicing.invoice.domain.model.SendStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Story 20.8 — the send outbox ({@code invoice_send_requests}) of the bound tenant: queued offline sendings and the log
 * of online ones. Runs in the caller's transaction, except the claims, which are single statements.
 */
public interface InvoiceSendRequestRepository {

    /**
     * Inserts a queued request unless one already exists for its {@code (operationId, channel)} (unique index).
     *
     * @return true when inserted, false when the operation was already queued (nothing written)
     */
    boolean insertIfAbsent(InvoiceSendRequest request);

    /** Inserts the log of an online sending. */
    void insert(InvoiceSendRequest request);

    /**
     * Claims one due PENDING request ({@code UPDATE … SET status = 'SENDING', attempts + 1 WHERE status = 'PENDING'
     * AND next_attempt_at <= now RETURNING …}): empty when another worker took it, it is not due or already done.
     */
    Optional<InvoiceSendRequest> claim(UUID id, Instant now);

    /**
     * Claims up to {@code limit} due PENDING requests in one statement (oldest due first, {@code FOR UPDATE SKIP
     * LOCKED}: concurrent workers never claim the same row).
     */
    List<InvoiceSendRequest> claimDue(Instant now, int limit);

    /** Ends a claimed request: SENT / FAILED / UNKNOWN, or back to PENDING at {@code nextAttemptAt} (retry). */
    void complete(UUID id, SendStatus status, String errorCode, Instant nextAttemptAt, Instant now);

    /**
     * SENDING rows claimed before {@code leaseStart} (worker gone mid-send): the message may have left, so they end
     * UNKNOWN (never retried). @return their invoice ids
     */
    List<InvoiceSendRequest> expireStale(Instant leaseStart, Instant now);

    /** Rows of a sync operation (test and diagnostics). */
    List<InvoiceSendRequest> findByOperation(String operationId);

    /** One catalogue query: the tenant schemas holding the outbox table (invoicing at V18+). */
    Set<String> schemasWithOutbox();
}
