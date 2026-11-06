package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.port.out.NotificationInboxRepository;
import com.keevo.messaging.notification.domain.port.out.NotificationInboxRepository.Purge;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.LiveTenant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Story 19.11 — nightly retention of the notification inbox ({@code "<schema>".notifications}, messaging V2), at 03:50
 * Douala time by default ({@code keevo.notification-retention.cron}). Template Method over the live tenants, the same
 * source as {@code ModuleExpiryMaterializer} ({@link PlanModuleRowStore#liveTenants()}: status other than
 * {@code DELETED}, schema present): per tenant, in its own transaction, two set-based statements behind the inbox port
 * ({@link NotificationInboxRepository#purge}) — rows read more than {@code keevo.notification-retention.read-days}
 * (default {@value #DEFAULT_READ_DAYS}) days ago, and every row of a user without an active membership in this tenant.
 * Unread rows are never purged by age. A schema without the inbox table is skipped; a tenant whose transaction fails
 * is logged by code and counted, the others proceed. One summary line per run.
 *
 * <p>Complexity: one catalogue query for the schemas holding the inbox, then 2 statements per tenant (the minimum
 * with schema-per-tenant): the age purge is driven by the partial {@code read_at} index; the departed-user purge scans
 * the tenant's inbox (small, since read rows are purged) and probes memberships by their unique index. Runs only when scheduling is on ({@code keevo.scheduling.enabled}, {@code SchedulingConfig}); one
 * instance per tick (ShedLock).
 */
@Component
public class NotificationRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetentionJob.class);

    static final int DEFAULT_READ_DAYS = 90;

    private final PlanModuleRowStore tenants;
    private final NotificationInboxRepository inbox;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int readDays;

    public NotificationRetentionJob(PlanModuleRowStore tenants, NotificationInboxRepository inbox,
                                    PlatformTransactionManager transactionManager, Clock clock,
                                    @Value("${keevo.notification-retention.read-days:" + DEFAULT_READ_DAYS + "}")
                                    int readDays) {
        if (readDays < 1) {
            throw new IllegalArgumentException("keevo.notification-retention.read-days must be >= 1");
        }
        this.tenants = tenants;
        this.inbox = inbox;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.readDays = readDays;
    }

    /**
     * @param tenants  tenants whose inbox was purged
     * @param skipped  live tenants without an inbox table (messaging tables absent) or with an invalid schema
     * @param read     rows deleted because read before the cut-off
     * @param departed rows deleted because their user left the tenant
     * @param failures tenants whose transaction failed (rolled back, retried next night)
     * @param ms       duration of the run
     */
    public record Report(int tenants, int skipped, long read, long departed, int failures, long ms) {
    }

    @Scheduled(cron = "${keevo.notification-retention.cron:0 50 3 * * *}", zone = "Africa/Douala")
    @SchedulerLock(name = "notificationRetentionJob.purge", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void scheduled() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.error("Notification retention: nightly run failed ({})", e.getClass().getSimpleName(), e);
        }
    }

    /** One run over every live tenant. */
    public Report purge() {
        long startedAt = System.nanoTime();
        Instant readBefore = clock.instant().minus(Duration.ofDays(readDays));
        int processed = 0;
        int skipped = 0;
        long read = 0;
        long departed = 0;
        int failures = 0;
        Set<String> withInbox = inbox.schemasWithInbox();
        for (LiveTenant tenant : tenants.liveTenants()) {
            if (!tenant.schemaPresent() || !TenantSchema.isValid(tenant.tenantSchema())
                    || !withInbox.contains(tenant.tenantSchema())) {
                skipped++; // the platform tenant ("public"), a schema already gone, or no messaging tables
                continue;
            }
            Purge purge;
            try {
                purge = transactions.execute(status -> inbox.purge(tenant.tenantSchema(), tenant.id(), readBefore));
            } catch (RuntimeException e) {
                failures++;
                log.warn("Notification retention: tenant {} skipped ({}) — rows left unchanged", tenant.code(),
                        e.getClass().getSimpleName());
                continue;
            }
            if (purge == null) {
                purge = new Purge(0, 0);
            }
            processed++;
            read += purge.read();
            departed += purge.departed();
        }
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Notification retention: {} tenant(s) purged, {} skipped, {} failed — {} read row(s) older than {} "
                + "days and {} row(s) of departed users deleted, {} ms", processed, skipped, failures, read, readDays,
                departed, ms);
        return new Report(processed, skipped, read, departed, failures, ms);
    }
}
