package com.keevo.reporting.report.application.service;

import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.model.WeeklyReportData;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

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
                    weeklyCommand.weekEnd(),    // closedAt = weekEnd (Sunday)
                    weeklyCommand.weekStart(),  // windowStart = weekStart (Monday)
                    weeklyCommand.deliveryChannel()
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

        WeeklyReportData weeklyData = builder.build(
                command.storeId(),
                command.windowStart(),
                command.closedAt(),
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
