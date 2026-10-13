package com.keevo.shared.infrastructure.config;

import com.keevo.reporting.export.application.service.ExportPurgeScheduler;
import com.keevo.commerce.sale.application.service.DayClosureAutoScheduler;
import com.keevo.identity.auth.application.service.AuthTokenCleanupJob;
import com.keevo.messaging.notification.application.listener.ProductCreationNotificationListener;
import com.keevo.messaging.notification.application.listener.StockAlertNotificationListener;
import com.keevo.messaging.notification.application.listener.StockModificationNotificationListener;
import com.keevo.messaging.notification.application.scheduler.SalesTrendDetectionScheduler;
import com.keevo.reporting.report.application.service.ReportDeliveryRetryService;
import com.keevo.reporting.report.application.service.WeeklyReportScheduler;
import com.keevo.shared.infrastructure.activity.UserActivityPurgeJob;
import com.keevo.shared.infrastructure.persistence.SyncKeysetIndexJob;
import com.keevo.shared.infrastructure.scheduling.AccountDeletionScheduler;
import com.keevo.shared.infrastructure.scheduling.PlanExpiryReminderScheduler;
import com.keevo.shared.infrastructure.scheduling.SubscriptionExpiryScheduler;
import com.keevo.sync.sync.application.service.SyncErrorLogCleanupJob;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Story 13.9 — every DB-driven {@code @Scheduled} job carries {@code @SchedulerLock} (one runner
 * per tick across instances) with bounds shorter than its period; the in-memory batch
 * flush/evict sweeps deliberately do not (each instance owns its own map).
 */
@DisplayName("ShedLock coverage of scheduled jobs (Story 13.9)")
class SchedulerLockCoverageTest {

    /**
     * DB-driven scheduled jobs → upper bound for lockAtMostFor: the period for crons. The 5-min
     * retry job is {@code fixedDelay} (next run starts 5 min after the previous one ENDS), so its
     * lock may outlive the delay; it gets PT15M so a slow batch is never resent by another
     * instance, bounded here at 20 min.
     */
    private static final Map<Class<?>, Duration> LOCKED_JOBS = Map.ofEntries(
            Map.entry(DayClosureAutoScheduler.class, Duration.ofHours(1)),
            Map.entry(AccountDeletionScheduler.class, Duration.ofDays(1)),
            Map.entry(SalesTrendDetectionScheduler.class, Duration.ofHours(1)),
            Map.entry(AuthTokenCleanupJob.class, Duration.ofDays(1)),
            Map.entry(SubscriptionExpiryScheduler.class, Duration.ofDays(1)),
            Map.entry(WeeklyReportScheduler.class, Duration.ofHours(1)),
            Map.entry(ReportDeliveryRetryService.class, Duration.ofMinutes(20)),
            Map.entry(SyncErrorLogCleanupJob.class, Duration.ofDays(1)),
            Map.entry(ExportPurgeScheduler.class, Duration.ofHours(1)), // v1s-18-1
            Map.entry(SyncKeysetIndexJob.class, Duration.ofDays(1)), // 11-5
            Map.entry(PlanExpiryReminderScheduler.class, Duration.ofDays(1)), // 9-2
            Map.entry(UserActivityPurgeJob.class, Duration.ofDays(1))); // 9-4

    /** In-memory sweeps: must run on every instance → never locked. */
    private static final List<Class<?>> IN_MEMORY_SWEEPS = List.of(
            ProductCreationNotificationListener.class,
            StockModificationNotificationListener.class,
            StockAlertNotificationListener.class);

    @Test
    @DisplayName("every @Scheduled method of the DB-driven jobs has @SchedulerLock with sane bounds")
    void dbJobsAreLocked() {
        Set<String> names = new HashSet<>();
        LOCKED_JOBS.forEach((type, period) -> {
            List<Method> scheduled = scheduledMethods(type);
            assertThat(scheduled).as("%s has a @Scheduled method", type.getSimpleName()).isNotEmpty();
            for (Method m : scheduled) {
                SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
                assertThat(lock).as("@SchedulerLock on %s.%s", type.getSimpleName(), m.getName()).isNotNull();
                assertThat(lock.name()).isNotBlank().hasSizeLessThanOrEqualTo(64);
                assertThat(names.add(lock.name())).as("lock name %s is unique", lock.name()).isTrue();

                Duration atMost = Duration.parse(lock.lockAtMostFor());
                Duration atLeast = Duration.parse(lock.lockAtLeastFor());
                assertThat(atMost).as("lockAtMostFor < bound for %s", lock.name()).isLessThan(period);
                assertThat(atLeast).as("lockAtLeastFor ≥ 30 s for %s", lock.name())
                        .isGreaterThanOrEqualTo(Duration.ofSeconds(30));
                assertThat(atLeast).isLessThan(atMost);
                if (period.compareTo(Duration.ofHours(1)) >= 0) {
                    assertThat(atLeast).as("cron jobs: lockAtLeastFor ≥ 1 min (%s)", lock.name())
                            .isGreaterThanOrEqualTo(Duration.ofMinutes(1));
                }
            }
        });
    }

    @Test
    @DisplayName("in-memory flush/evict sweeps are scheduled but NOT locked")
    void inMemorySweepsAreNotLocked() {
        for (Class<?> type : IN_MEMORY_SWEEPS) {
            List<Method> scheduled = scheduledMethods(type);
            assertThat(scheduled).as("%s has a sweep", type.getSimpleName()).isNotEmpty();
            scheduled.forEach(m -> assertThat(m.getAnnotation(SchedulerLock.class))
                    .as("%s.%s must run on every instance", type.getSimpleName(), m.getName()).isNull());
        }
    }

    @Test
    @DisplayName("every @Scheduled class of the codebase is classified (locked job or in-memory sweep)")
    void everyScheduledClassIsClassified() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) ->
                reader.getAnnotationMetadata().hasAnnotatedMethods(Scheduled.class.getName()));
        Set<String> found = new HashSet<>();
        scanner.findCandidateComponents("com.keevo").forEach(bd -> found.add(bd.getBeanClassName()));

        Set<String> classified = new HashSet<>();
        LOCKED_JOBS.keySet().forEach(c -> classified.add(c.getName()));
        IN_MEMORY_SWEEPS.forEach(c -> classified.add(c.getName()));
        assertThat(found).as("new @Scheduled job? add @SchedulerLock and list it here")
                .containsExactlyInAnyOrderElementsOf(classified);
    }

    @Test
    @DisplayName("SchedulerLockConfig enables ShedLock and exposes a JDBC LockProvider")
    void lockProviderBean() {
        assertThat(SchedulerLockConfig.class.getAnnotation(EnableSchedulerLock.class)).isNotNull();
        LockProvider provider = new SchedulerLockConfig().lockProvider(mock(DataSource.class));
        assertThat(provider).isInstanceOf(JdbcTemplateLockProvider.class);
    }

    @Test
    @DisplayName("every use case taking a TransactionLockPort lock runs inside a Spring transaction")
    void lockTakingUseCasesAreTransactional() throws Exception {
        // The advisory-lock adapter is Propagation.MANDATORY and unit tests mock the port:
        // this guards that the production entry points actually open a transaction.
        Map<Class<?>, String> lockTakers = Map.ofEntries(
                Map.entry(com.keevo.catalog.product.application.usecase.CreateProductUseCase.class, "execute"),
                Map.entry(com.keevo.catalog.product.application.usecase.CreateDraftProductUseCase.class, "execute"),
                Map.entry(com.keevo.catalog.stock.application.usecase.ExecuteTransferService.class, "execute"),
                Map.entry(com.keevo.commerce.sale.application.service.CloseDayService.class, "closeDay"),
                Map.entry(com.keevo.identity.employee.application.service.ChangeEmployeeRoleService.class, "execute"),
                Map.entry(com.keevo.identity.employee.application.service.DeactivateEmployeeService.class, "execute"),
                // v1s-13-13
                Map.entry(com.keevo.store.store.application.service.CreateStoreService.class, "execute"),
                Map.entry(com.keevo.identity.employee.application.service.CreateEmployeeService.class, "execute"),
                Map.entry(com.keevo.reporting.report.application.service.WeeklyReportGenerator.class, "generateWeeklyReport"),
                // v1s-14-13
                Map.entry(com.keevo.identity.employee.application.service.ReactivateEmployeeService.class, "execute"),
                // v1s-14-13 (quick-add)
                Map.entry(com.keevo.inventory.counting.application.service.QuickAddProductService.class, "execute"));
        lockTakers.forEach((type, methodName) -> {
            List<Method> methods = Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.getName().equals(methodName) && !m.isSynthetic() && !m.isBridge())
                    .toList();
            assertThat(methods).as("%s.%s exists", type.getSimpleName(), methodName).isNotEmpty();
            boolean classTx = type.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class);
            methods.forEach(m -> assertThat(classTx
                    || m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                    .as("@Transactional on %s.%s", type.getSimpleName(), methodName).isTrue());
        });
    }

    private static List<Method> scheduledMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .toList();
    }
}
