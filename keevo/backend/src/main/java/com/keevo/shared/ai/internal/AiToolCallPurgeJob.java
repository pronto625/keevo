package com.keevo.shared.ai.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Story 21.8 — nightly retention of the AI tool call audit: rows older than {@value #RETENTION_DAYS} days are deleted
 * in one set-based DELETE (range on {@code idx_ai_tool_calls_called_at}); one runner across instances (ShedLock).
 */
@Component
public class AiToolCallPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AiToolCallPurgeJob.class);

    static final int RETENTION_DAYS = 180;

    private final AiToolCallAudit audit;
    private final Clock clock;

    public AiToolCallPurgeJob(AiToolCallAudit audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(cron = "${keevo.ai-tool-call-purge.cron:0 50 3 * * *}", zone = "Africa/Douala")
    @SchedulerLock(name = "aiToolCallPurgeJob.purge", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void purge() {
        try {
            int deleted = audit.purgeBefore(clock.instant().minus(Duration.ofDays(RETENTION_DAYS)));
            if (deleted > 0) {
                log.info("AI tool call purge: {} row(s) older than {} days deleted", deleted, RETENTION_DAYS);
            }
        } catch (RuntimeException e) {
            log.warn("AI tool call purge failed ({}: {})", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
