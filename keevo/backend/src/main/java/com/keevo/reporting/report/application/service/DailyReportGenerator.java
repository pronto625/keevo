package com.keevo.reporting.report.application.service;

import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
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
import java.util.UUID;

/**
 * DailyReportGenerator — Concrete implementation of AbstractReportGenerator.
 * Story 7.2 — Task 6.3 (GREEN).
 *
 * <p>Overrides: collectData (via EndOfDayReportBuilder) + formatContent (via DailyReportFormatter).
 * Routes to employee-scoped build + format when command.actorId() is non-null.
 */
@Service
public class DailyReportGenerator extends AbstractReportGenerator
        implements GenerateEndOfDayReportUseCase {

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

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

        Instant windowStart = command.windowStart() != null
                ? command.windowStart()
                : reportDate.atStartOfDay(WAT).toInstant();
        Instant windowEnd = command.closedAt() != null ? command.closedAt() : Instant.now();

        if (command.actorId() != null) {
            return builder.buildForEmployee(command.storeId(), command.actorId(),
                    windowStart, windowEnd, storeName, reportDate, closeTime, command.isAutomatic());
        }
        return builder.build(command.storeId(), windowStart, windowEnd,
                storeName, reportDate, closeTime, command.isAutomatic());
    }

    @Override
    protected String formatContent(EndOfDayReportData data) {
        return data.isForEmployee() ? formatter.formatEmployee(data) : formatter.format(data);
    }

    @Override
    protected ReportType getReportType() {
        return ReportType.DAILY;
    }

    @Override
    protected String resolveOwnerPhone(GenerateReportCommand command) {
        return userRepository.findOwnerByTenantSchemaName(command.tenantId())
                .map(u -> u.getPhoneNumber())
                .orElse(null); // null → AbstractReportGenerator.deliverReport marks IN_APP_ONLY
    }

    /** Employee reports are stored in DB only — not delivered via WhatsApp. */
    @Override
    protected void deliverReport(EndOfDayReport report, GenerateReportCommand command) {
        if (command.actorId() != null) {
            return;
        }
        super.deliverReport(report, command);
    }

    /** Multi-store summary is only triggered once (for the store report, actorId=null). */
    @Override
    protected void checkMultiStoreCondition(GenerateReportCommand command) {
        if (command.actorId() == null) {
            super.checkMultiStoreCondition(command);
        }
    }

}

