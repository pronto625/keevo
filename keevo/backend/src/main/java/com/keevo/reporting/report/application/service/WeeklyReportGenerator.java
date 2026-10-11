package com.keevo.reporting.report.application.service;

import com.keevo.commerce.sale.domain.model.ClosureWindow;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.model.WeeklyReportData;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    public WeeklyReportGenerator(EndOfDayReportRepository reportRepository,
                                  WhatsAppPort whatsAppPort,
                                  MultiStoreSummaryService multiStoreSummaryService,
                                  WeeklyReportBuilder builder,
                                  WeeklyReportFormatter formatter,
                                  StoreRepository storeRepository,
                                  UserRepository userRepository) {
        super(reportRepository, whatsAppPort, multiStoreSummaryService);
        this.builder = builder;
        this.formatter = formatter;
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
    }

    // ── GenerateWeeklyReportUseCase ────────────────────────────────────────────

    @Override
    public EndOfDayReport generateWeeklyReport(WeeklyReportCommand weeklyCommand) {
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
        String storeName = storeRepository.findById(command.storeId())
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
        return userRepository.findOwnerByTenantSchemaName(command.tenantId())
                .map(u -> u.getPhoneNumber())
                .orElse(null);
    }
}
