package com.keevo.shared.infrastructure.scheduling;

import com.keevo.admin.alerts.domain.port.in.PurgeAlertAcksUseCase;
import com.keevo.admin.platform.domain.port.in.RecordHealthSampleUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PlatformHealthSampleScheduler — one health sample every 5 minutes (Story 9-5, FR83), then the
 * purge of alert acks older than 90 days. ShedLock: one instance per tick. A failed tick leaves a
 * missing sample, counted as down by the uptime.
 *
 * <p>The period is fixed (not a property): the uptime computation expects exactly one sample per
 * {@code PlatformHealthService.SAMPLE_PERIOD}. The in-memory deltas assume a single backend instance
 * ({@code HealthSampleService}).
 */
@Component
public class PlatformHealthSampleScheduler {

    private static final Logger log = LoggerFactory.getLogger(PlatformHealthSampleScheduler.class);

    private final RecordHealthSampleUseCase recordHealthSampleUseCase;
    private final PurgeAlertAcksUseCase purgeAlertAcksUseCase;

    public PlatformHealthSampleScheduler(RecordHealthSampleUseCase recordHealthSampleUseCase,
                                         PurgeAlertAcksUseCase purgeAlertAcksUseCase) {
        this.recordHealthSampleUseCase = recordHealthSampleUseCase;
        this.purgeAlertAcksUseCase = purgeAlertAcksUseCase;
    }

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "platformHealthSampleScheduler.sample", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void sample() {
        try {
            recordHealthSampleUseCase.recordSample();
        } catch (Exception e) {
            log.error("[PlatformHealthSampleScheduler] Health sample not recorded: {}", e.getMessage(), e);
        }
        try {
            purgeAlertAcksUseCase.purgeOldAcks();
        } catch (Exception e) {
            log.warn("[PlatformHealthSampleScheduler] Alert acks not purged: {}", e.getMessage());
        }
    }
}
