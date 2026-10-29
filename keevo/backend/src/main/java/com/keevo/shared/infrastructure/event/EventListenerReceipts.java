package com.keevo.shared.infrastructure.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;

/**
 * EventListenerReceipts — delivery receipts of the {@code @DeliverOnce} module listeners (Story 12.4),
 * table {@code public.event_listener_receipt} (Flyway V28).
 *
 * <p>Every write runs in its own {@code REQUIRES_NEW} transaction: the receipt is committed before the
 * listener sends anything, independently of the listener's own transaction and of whatever the calling
 * thread had bound (a caller-runs fallback executes the listener in the publisher's thread).
 */
@Component
public class EventListenerReceipts {

    private static final String INSERT = """
            INSERT INTO public.event_listener_receipt (listener_id, event_hash, received_at)
            VALUES (?, ?, NOW())
            ON CONFLICT (listener_id, event_hash) DO NOTHING
            """;
    private static final String DELETE = "DELETE FROM public.event_listener_receipt WHERE listener_id = ? AND event_hash = ?";
    private static final String PURGE = "DELETE FROM public.event_listener_receipt WHERE received_at < ?";

    /**
     * Receipts are kept 30 days — far beyond the 24 h stale-publication alert of
     * {@link EventPublicationResubmissionJob}: an incomplete publication is retried (and investigated) long
     * before its receipt could be purged, so a late replay still finds the receipt and is not re-sent.
     */
    public static final java.time.Duration RETENTION = java.time.Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    public EventListenerReceipts(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return {@code true} if this is the first delivery of the event to the listener (receipt taken). */
    public boolean tryAcquire(String listenerId, String eventHash) {
        Integer inserted = requiresNew.execute(status -> jdbc.update(INSERT, listenerId, eventHash));
        return inserted != null && inserted == 1;
    }

    /** Drops the receipt after a failed delivery, so a resubmission of the publication retries it. */
    public void release(String listenerId, String eventHash) {
        requiresNew.executeWithoutResult(status -> jdbc.update(DELETE, listenerId, eventHash));
    }

    /** @return number of receipts older than {@code retention} removed. */
    public int purgeOlderThan(Duration retention) {
        Integer deleted = requiresNew.execute(status ->
                jdbc.update(PURGE, java.sql.Timestamp.from(Instant.now().minus(retention))));
        return deleted == null ? 0 : deleted;
    }
}
