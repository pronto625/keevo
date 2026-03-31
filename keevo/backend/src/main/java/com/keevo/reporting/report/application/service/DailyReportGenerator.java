package com.keevo.reporting.report.application.service;

import com.keevo.commerce.sale.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * DailyReportGenerator — Concrete implementation of AbstractReportGenerator.
 * Story 7.2 — Task 6.3 (GREEN).
 *
 * <p>Overrides: collectData (via EndOfDayReportBuilder) + formatContent (via DailyReportFormatter).
 * Future WeeklyReportGenerator (story 7.3) extends the same AbstractReportGenerator.
 */
@Service
public class DailyReportGenerator extends AbstractReportGenerator
        implements GenerateEndOfDayReportUseCase {

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    private static final String DEFAULT_OWNER_PHONE = "+243000000000";

    private final EndOfDayReportBuilder builder;
    private final DailyReportFormatter formatter;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    public DailyReportGenerator(EndOfDayReportRepository reportRepository,
                                 WhatsAppPort whatsAppPort,
                                 MultiStoreSummaryService multiStoreSummaryService,
                                 EndOfDayReportBuilder builder,
                                 DailyReportFormatter formatter,
                                 StoreRepository storeRepository,
                                 UserRepository userRepository) {
        super(reportRepository, whatsAppPort, multiStoreSummaryService);
        this.builder = builder;
        this.formatter = formatter;
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
    }

    @Override
    protected EndOfDayReportData collectData(GenerateReportCommand command) {
        String storeName = storeRepository.findById(command.storeId())
                .map(s -> s.name())
                .orElse("Boutique #" + command.storeId().toString().substring(0, 8));

        LocalDate reportDate = command.closedAt() != null
                ? command.closedAt().atZone(WAT).toLocalDate()
                : LocalDate.now(WAT);

        LocalTime closeTime = command.closedAt() != null
                ? command.closedAt().atZone(WAT).toLocalTime()
                : LocalTime.now(WAT);

        return builder.build(command.storeId(), reportDate, storeName, closeTime, command.isAutomatic());
    }

    @Override
    protected String formatContent(EndOfDayReportData data) {
        return formatter.format(data);
    }

    @Override
    protected ReportType getReportType() {
        return ReportType.DAILY;
    }

    @Override
    protected String resolveOwnerPhone(GenerateReportCommand command) {
        return userRepository.findOwnerByTenantSchemaName(command.tenantId())
                .map(u -> u.getPhoneNumber())
                .orElse(DEFAULT_OWNER_PHONE);
    }
}
