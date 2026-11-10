package com.keevo.shared.ai;

import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story 21.4.2 — the AI credits (19.8 {@code AI_CREDITS}) of a paid AI use (an assistant question, an MCP tool call):
 * <b>reserve</b> before the paid work, <b>consume</b> once it succeeded, <b>release</b> in any case.
 *
 * <p><b>Pattern:</b> reservation (check-then-act made exact). A reservation checks, by the very guarded increment of
 * 19.8 inside a transaction that is rolled back, that the company still has one credit <em>more than the uses already
 * in flight</em> — so N members asking at once with one credit left get one answer and N − 1 exact
 * {@code QUOTA_EXCEEDED} refusals, instead of N answers of which N − 1 go uncounted (the review of 2026-11-10). The
 * credit is consumed after the work by one guarded increment (committed); a failure consumes nothing. In-flight uses
 * are counted in memory per company (the application runs as one instance, like the in-flight guards of 21.4 and the
 * rate limits of 21.9); a consumer outside this class (none today) could still take the last credit meanwhile, the
 * use is then delivered uncounted and logged.
 *
 * <p><b>Cost:</b> one rolled-back quota transaction per reservation, one committed one per consumption; O(1) memory per
 * company with a use in flight (entries removed at zero).
 */
@Component
public class AiCredits {

    private static final Logger log = LoggerFactory.getLogger(AiCredits.class);

    private final UsageQuotaPort quota;
    private final TransactionTemplate tx;
    private final Map<String, Integer> inFlight = new ConcurrentHashMap<>();

    public AiCredits(UsageQuotaPort quota, TransactionTemplate tx) {
        this.quota = quota;
        this.tx = tx;
    }

    /**
     * Reserves one credit of company {@code tenantSchema} for a paid use; {@link Reservation#close()} releases it.
     *
     * @throws DomainException {@code QUOTA_EXCEEDED} with the details of 19.8 — nothing reserved
     */
    public Reservation reserve(String tenantSchema) {
        int[] wanted = new int[1];
        inFlight.compute(tenantSchema, (k, n) -> wanted[0] = (n == null ? 0 : n) + 1);
        try {
            check(tenantSchema, wanted[0]);
        } catch (RuntimeException e) {
            release(tenantSchema);
            throw e;
        }
        return new Reservation(tenantSchema);
    }

    /** Whether the company could reserve a credit now (nothing reserved, nothing counted). */
    public boolean available(String tenantSchema) {
        try {
            check(tenantSchema, inFlight.getOrDefault(tenantSchema, 0) + 1);
            return true;
        } catch (DomainException e) {
            return false;
        }
    }

    /** The 19.8 increment of {@code units} in a rolled-back transaction: the exact refusal or nothing kept. */
    private void check(String tenantSchema, int units) {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            quota.assertAndIncrementForSchema(tenantSchema, UsageMetric.AI_CREDITS, units);
        });
    }

    private void release(String tenantSchema) {
        inFlight.computeIfPresent(tenantSchema, (k, n) -> n <= 1 ? null : n - 1);
    }

    /** In-flight uses of a company (tests). */
    int inFlight(String tenantSchema) {
        return inFlight.getOrDefault(tenantSchema, 0);
    }

    /** One reserved credit: consumed at most once, released by {@link #close()} (idempotent). */
    public final class Reservation implements AutoCloseable {

        private final String tenantSchema;
        private boolean consumed;
        private boolean closed;

        private Reservation(String tenantSchema) {
            this.tenantSchema = tenantSchema;
        }

        /**
         * Counts the credit (committed). Never throws: the paid work is done, so a refusal (a consumer outside this
         * class took the last credit) or an unavailable counter leaves the use delivered uncounted, logged.
         *
         * @return whether the credit was counted
         */
        public synchronized boolean consume() {
            if (consumed || closed) {
                return false;
            }
            consumed = true;
            try {
                tx.executeWithoutResult(status -> quota.assertAndIncrementForSchema(tenantSchema,
                        UsageMetric.AI_CREDITS, 1));
                return true;
            } catch (DomainException e) {
                log.warn("[AI] AI credit not counted ({}): use delivered", e.getErrorCode() == ErrorCode.QUOTA_EXCEEDED
                        ? "credits used up meanwhile" : e.getDomainCode());
            } catch (RuntimeException e) {
                log.warn("[AI] AI credit not counted ({}): use delivered", e.getClass().getSimpleName());
            }
            return false;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                release(tenantSchema);
            }
        }
    }
}
