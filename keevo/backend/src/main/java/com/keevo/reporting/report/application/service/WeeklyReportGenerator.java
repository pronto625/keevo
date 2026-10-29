package com.keevo.reporting.report.application.service;

import com.keevo.commerce.api.ClosureWindow;
import com.keevo.messaging.api.WhatsAppPort;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.api.ReportChannel;
import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.model.WeeklyReportData;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * WeeklyReportGenerator — Concrete implementation of AbstractReportGenerator for weekly reports.
 * Story 7.3 — Rapport Hebdomadaire Automatique (AC2).
 *
 * <p>GoF Template Method: overrides collectData() + formatContent() + getReportType().
 * WeeklyDataHolder (ThreadLocal) bridges the rich WeeklyReportData between collectData()
 * and formatContent() while the parent template uses a minimal EndOfDayReportData for persistence.
 *
 * <p>Entry point: generateWeeklyReport(WeeklyReportCommand) which adapts the command
 * and delegates to the parent generateReport() template.
 */
@Service
public class WeeklyReportGenerator extends AbstractReportGenerator
        implements GenerateWeeklyReportUseCase {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportGenerator.class);
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    private final WeeklyReportBuilder builder;
    private final WeeklyReportFormatter formatter;
    private final StoreQueryPort storeQuery;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final TransactionLockPort transactionLock;
    private final TransactionTemplate afterCommitTx;

    public WeeklyReportGenerator(EndOfDayReportRepository reportRepository,
                                  WhatsAppPort whatsAppPort,
                                  MultiStoreSummaryService multiStoreSummaryService,
                                  WeeklyReportBuilder builder,
                                  WeeklyReportFormatter formatter,
                                  StoreQueryPort storeQuery,
                                  OwnerRecipientResolver ownerRecipientResolver,
                                  TransactionLockPort transactionLock,
                                  PlatformTransactionManager transactionManager) {
        super(reportRepository, whatsAppPort, multiStoreSummaryService);
        this.builder = builder;
        this.formatter = formatter;
        this.storeQuery = storeQuery;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.transactionLock = transactionLock;
        // afterCommit still sees the committed transaction's resources bound: writes there
        // must open their own transaction or they are never committed.
        this.afterCommitTx = new TransactionTemplate(transactionManager);
        this.afterCommitTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── GenerateWeeklyReportUseCase ────────────────────────────────────────────

    /**
     * Transactional entry point (v1s-13-13): the call to {@link #generateReport} below is a
     * self-invocation (no proxy), so this method owns the transaction. The per-store/week
     * advisory lock is taken first, so a scheduled run and a manual trigger for the same
     * store/Sunday serialise: the loser's {@code findByKey} guard then sees the winner's row and
     * returns it (no second report, no second WhatsApp send).
     */
    @Override
    @Transactional
    public EndOfDayReport generateWeeklyReport(WeeklyReportCommand weeklyCommand) {
        transactionLock.lock("weekly-report:" + weeklyCommand.storeId() + ":"
                + weeklyCommand.weekEnd().atZone(WAT).toLocalDate());
        try {
            GenerateEndOfDayReportUseCase.GenerateReportCommand adapted = new GenerateEndOfDayReportUseCase.GenerateReportCommand(
                    weeklyCommand.storeId(),
                    null,  // no actor for scheduled/manual weekly
                    weeklyCommand.tenantId(),
                    weeklyCommand.isAutomatic(),
                    weeklyCommand.weekEnd(),    // closedAt = window end (its WAT date = the Sunday)
                    weeklyCommand.weekStart(),  // windowStart (see WeeklyReportWindowResolver)
                    weeklyCommand.weekEnd(),    // windowEnd
                    weeklyCommand.deliveryChannel(),
                    weeklyCommand.lateFrom(),
                    weeklyCommand.receivedUntil()
            );
            return generateReport(adapted);
        } finally {
            WeeklyDataHolder.clear();
        }
    }

    // ── Template Method overrides ──────────────────────────────────────────────

    @Override
    protected EndOfDayReportData collectData(GenerateEndOfDayReportUseCase.GenerateReportCommand command) {
        String storeName = storeQuery.findById(TenantContext.getCurrentTenant(), command.storeId())
                .map(s -> s.name())
                .orElse("Boutique #" + command.storeId().toString().substring(0, 8));

        LocalDate weekEndDate = command.closedAt().atZone(WAT).toLocalDate();

        // v1s-13-11 (D2): same occurrence/arrival rule as day closures, so late offline sales
        // and the Sunday evening after a Sunday-20:00 report land in exactly one weekly report.
        ClosureWindow window = new ClosureWindow(
                command.windowStart(), command.closedAt(), command.lateFrom(), command.receivedUntil());

        WeeklyReportData weeklyData = builder.build(
                command.storeId(),
                window,
                storeName,
                weekEndDate,
                command.tenantId(),
                command.isAutomatic()
        );

        // Store in ThreadLocal for formatContent()
        WeeklyDataHolder.set(weeklyData);

        // Return minimal EndOfDayReportData for the parent's persistReport() step
        return new EndOfDayReportData(
                storeName, weekEndDate, LocalTime.of(23, 59), command.isAutomatic(), false,
                weeklyData.totalSales(), weeklyData.totalRevenue(),
                weeklyData.cashAmount(), weeklyData.momoAmount(), weeklyData.avgBasket(),
                List.of(), List.of(), weeklyData.lowStockCount(), 0, 0, null
        );
    }

    /**
     * Persist with {@code createdAt = receivedUntil}: the next weekly report chains on this
     * instant (its lateFrom/start), so it must be exactly the arrival limit used here.
     */
    @Override
    protected EndOfDayReport persistReport(GenerateEndOfDayReportUseCase.GenerateReportCommand command,
                                           EndOfDayReportData data, String content) {
        if (command.receivedUntil() == null) {
            return super.persistReport(command, data, content);
        }
        EndOfDayReport report = new EndOfDayReport(
                UUID.randomUUID(), command.tenantId(), command.storeId(), data.storeName(),
                command.actorId(), data.employeeName(),
                getReportType(), data.reportDate(), content,
                DeliveryStatus.PENDING, 0, null,
                data.totalRevenue(), data.totalSales(), command.isAutomatic(),
                command.receivedUntil());
        reportRepository.save(report);
        log.info("Report persisted: id={}, store={}, type={}", report.getId(), command.storeId(), getReportType());
        return report;
    }

    /**
     * v1s-13-13: the WhatsApp send runs after the report transaction commits (lock and
     * connection released, no external HTTP call under the advisory lock), so a rolled-back
     * report is never sent and a sent report is never rolled back (no re-send next tick).
     * It runs in a REQUIRES_NEW transaction (data access in {@code afterCommit} would otherwise
     * join the already-committed transaction and its status save would never be committed).
     * Without an active transaction synchronization (plain call) it runs immediately.
     */
    @Override
    protected void deliverReport(EndOfDayReport report,
                                 GenerateEndOfDayReportUseCase.GenerateReportCommand command) {
        afterCommit(() -> super.deliverReport(report, command));
    }

    /** v1s-13-13: same after-commit rule as {@link #deliverReport}. */
    @Override
    protected void checkMultiStoreCondition(GenerateEndOfDayReportUseCase.GenerateReportCommand command) {
        afterCommit(() -> super.checkMultiStoreCondition(command));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // The report is already committed: a failure here (status save, opening the
                    // REQUIRES_NEW transaction) must neither reach the caller (manual trigger loop)
                    // nor skip the next synchronization. A report left PENDING is picked up by
                    // ReportDeliveryRetryService once stale.
                    try {
                        afterCommitTx.executeWithoutResult(status -> action.run());
                    } catch (RuntimeException e) {
                        log.error("After-commit weekly report step failed: {}", e.getMessage(), e);
                    }
                }
            });
        } else {
            action.run();
        }
    }

    @Override
    protected String formatContent(EndOfDayReportData ignored) {
        WeeklyReportData weeklyData = WeeklyDataHolder.get();
        return formatter.format(weeklyData);
    }

    @Override
    protected ReportType getReportType() {
        return ReportType.WEEKLY;
    }

    @Override
    protected String resolveOwnerPhone(GenerateEndOfDayReportUseCase.GenerateReportCommand command) {
        return ownerRecipientResolver.resolveOwnerWhatsAppPhone(command.tenantId())
                .orElse(null); // null → AbstractReportGenerator.deliverReport marks IN_APP_ONLY
    }
}
