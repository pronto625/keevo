package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * CloseDayService — Implements day closure logic with Template Method pattern.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Steps (Template Method):
 * <ol>
 *   <li>Check no closure exists for storeId + today</li>
 *   <li>Load all sales for the day (today 00:00 — 23:59 in tenant timezone)</li>
 *   <li>Build summary using DayClosureSummaryBuilder (GoF Builder)</li>
 *   <li>Persist DayClosure aggregate</li>
 *   <li>Publish DayClosedEvent (triggers WhatsApp listener + audit)</li>
 * </ol>
 */
@Service
@Transactional
public class CloseDayService implements CloseDayUseCase {

    private final SaleRepository saleRepository;
    private final DayClosureRepository dayClosureRepository;
    private final ApplicationEventPublisher eventPublisher;

    // West Africa Time (WAT) = UTC+1
    private static final ZoneId WAT_ZONE = ZoneId.of("Africa/Lagos");

    public CloseDayService(SaleRepository saleRepository,
                           DayClosureRepository dayClosureRepository,
                           ApplicationEventPublisher eventPublisher) {
        this.saleRepository = saleRepository;
        this.dayClosureRepository = dayClosureRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public DayClosure closeDay(CloseDayCommand command) {
        // Story 13.3: automatic closures fire at per-tenant eodReportTime (default 20h WAT)
        // and close the CURRENT day, not yesterday.
        // Both automatic and manual closures use the current date in WAT.
        LocalDate reportDate = LocalDate.now(WAT_ZONE);

        // Step 1: Check no closure exists for the report date
        if (dayClosureRepository.existsByStoreIdAndDate(command.storeId(), reportDate)) {
            throw new DomainException(ErrorCode.DAY_ALREADY_CLOSED,
                    "La journée a déjà été clôturée pour cette boutique");
        }

        // Step 2: Fixed calendar window — 00:00:00 to 23:59:59.999 WAT of reportDate
        Instant windowStart = reportDate.atStartOfDay(WAT_ZONE).toInstant();
        Instant windowEnd   = reportDate.atTime(LocalTime.MAX).atZone(WAT_ZONE).toInstant();
        Instant now         = Instant.now();

        var salesPage = saleRepository.findByStoreIdAndDateRange(
                command.storeId(), windowStart, windowEnd, PageRequest.of(0, 10000));

        // Step 3: Build summary
        DayClosureSummary summary = new DayClosureSummaryBuilder()
                .addSales(salesPage.getContent())
                .build();

        // Step 4: Persist closure
        DayClosure closure = new DayClosure(
                UUID.randomUUID(),
                command.storeId(),
                command.actorId(),
                now,
                summary,
                command.isAutomatic(),
                command.tenantId()
        );
        dayClosureRepository.save(closure);

        // Step 5: Publish event with fixed calendar window
        DayClosedEvent event = new DayClosedEvent(
                closure.getId(),
                closure.getStoreId(),
                closure.getActorId(),
                summary,
                command.isAutomatic(),
                command.tenantId(),
                now,
                windowStart,
                windowEnd
        );
        eventPublisher.publishEvent(event);

        return closure;
    }
}
