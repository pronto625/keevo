package com.keevo.reporting.report.application.service;

import com.keevo.messaging.api.WhatsAppPort;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.api.EmployeeQueryPort;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreQueryPort;
import org.springframework.stereotype.Service;
import com.keevo.commerce.api.ClosureWindow;

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
    private final StoreQueryPort storeQuery;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final EmployeeQueryPort employeeQuery;

    public DailyReportGenerator(EndOfDayReportRepository reportRepository,
                                 WhatsAppPort whatsAppPort,
                                 MultiStoreSummaryService multiStoreSummaryService,
                                 EndOfDayReportBuilder builder,
                                 DailyReportFormatter formatter,
                                 StoreQueryPort storeQuery,
                                 OwnerRecipientResolver ownerRecipientResolver,
                                 EmployeeQueryPort employeeQuery) {
        super(reportRepository, whatsAppPort, multiStoreSummaryService);
        this.builder = builder;
        this.formatter = formatter;
        this.storeQuery = storeQuery;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.employeeQuery = employeeQuery;
    }

    @Override
    protected EndOfDayReportData collectData(GenerateReportCommand command) {
        String storeName = storeQuery.findById(TenantContext.getCurrentTenant(), command.storeId())
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
        Instant windowEnd = command.windowEnd() != null ? command.windowEnd()
                : (command.closedAt() != null ? command.closedAt() : Instant.now());
        // Same sales as the day closure: late offline sales included (see ClosureWindow).
        ClosureWindow window = new ClosureWindow(windowStart, windowEnd,
                command.lateFrom(), command.receivedUntil());

        if (command.actorId() != null) {
            String actorName = resolveEmployeeName(command.actorId());
            return builder.buildForEmployee(command.storeId(), command.actorId(),
                    window, storeName, reportDate, closeTime, command.isAutomatic(), actorName);
        }
        return builder.build(command.storeId(), window,
                storeName, reportDate, closeTime, command.isAutomatic());
    }

    /**
     * Prénom + nom du vendeur d'un rapport personnel. Le rapport porte l'id
     * UTILISATEUR (= {@code sales.employee_id}), pas l'id de la fiche
     * employé : chercher par {@code findById} seul ne trouvait jamais personne
     * et {@code actor_name} restait NULL (« Employé » dans l'app). L'id de fiche
     * reste accepté en repli pour les anciens appelants.
     */
    private String resolveEmployeeName(UUID actorId) {
        return employeeQuery.findByUserId(TenantContext.getCurrentTenant(), actorId)
                .or(() -> employeeQuery.findById(TenantContext.getCurrentTenant(), actorId))
                .map(e -> java.util.stream.Stream.of(e.firstName(), e.lastName())
                        .filter(p -> p != null && !p.isBlank())
                        .map(String::trim)
                        .collect(java.util.stream.Collectors.joining(" ")))
                .filter(name -> !name.isEmpty())
                .orElse(null);
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
        return ownerRecipientResolver.resolveOwnerWhatsAppPhone(command.tenantId())
                .orElse(null); // null → AbstractReportGenerator.deliverReport marks IN_APP_ONLY
    }

    /** Employee reports are stored in-app only — not delivered via WhatsApp. */
    @Override
    protected void deliverReport(EndOfDayReport report, GenerateReportCommand command) {
        if (command.actorId() != null) {
            // Mark explicitly as IN_APP_ONLY so the report never stays PENDING.
            report.markInAppOnly();
            reportRepository.save(report);
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

