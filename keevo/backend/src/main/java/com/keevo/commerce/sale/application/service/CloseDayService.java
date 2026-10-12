package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * CloseDayService — Implements day closure logic with Template Method pattern.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Steps (Template Method):
 * <ol>
 *   <li>Check no closure exists for storeId + the closure's day (WAT), nor a later one</li>
 *   <li>Load the sales the closure covers (see {@link ClosureWindow})</li>
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
    private final TransactionLockPort transactionLock;
    private final Clock clock;

    // West Africa Time (WAT) = UTC+1
    private static final ZoneId WAT_ZONE = ZoneId.of("Africa/Lagos");

    /** Oldest closure accepted: matches the 7-day offline sync gate. */
    public static final Duration MAX_BACKDATE = Duration.ofDays(7);

    public CloseDayService(SaleRepository saleRepository,
                           DayClosureRepository dayClosureRepository,
                           ApplicationEventPublisher eventPublisher,
                           TransactionLockPort transactionLock,
                           Clock clock) {
        this.saleRepository = saleRepository;
        this.dayClosureRepository = dayClosureRepository;
        this.eventPublisher = eventPublisher;
        this.transactionLock = transactionLock;
        this.clock = clock;
    }

    @Override
    public DayClosure closeDay(CloseDayCommand command) {
        // Story 13.9: serialise closures of a store (auto-close tick vs manual/sync close, or
        // two instances) so the exists-checks below and the save cannot interleave. Taken
        // first so the loser computes its window after the winner committed.
        transactionLock.lock("day-closure:" + command.storeId());

        // The closure time decides the closed day. An offline closure keeps the time it
        // was made on the device (never in the future, at most MAX_BACKDATE ago);
        // otherwise it is now (injected Clock, story 13.11).
        Instant now      = Instant.now(clock);
        Instant closedAt = command.closedAt() != null && !command.closedAt().isAfter(now)
                ? command.closedAt() : now;
        if (closedAt.isBefore(now.minus(MAX_BACKDATE))) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Clôture trop ancienne (plus de 7 jours) : elle n'est plus acceptée");
        }
        LocalDate reportDate = closedAt.atZone(WAT_ZONE).toLocalDate();

        // Step 1: Check no closure exists for the report date, with this id (retried push),
        // or after it: closures of a store only move forward, so an older offline closure
        // pushed after a newer one is refused instead of re-counting its sales.
        var previous = dayClosureRepository.findLastClosureForStore(command.storeId());
        if (dayClosureRepository.existsByStoreIdAndDate(command.storeId(), reportDate)
                || (command.closureId() != null && dayClosureRepository.existsById(command.closureId()))
                || previous.filter(p -> !p.getClosedAt().isBefore(closedAt)).isPresent()) {
            throw new DomainException(ErrorCode.DAY_ALREADY_CLOSED,
                    "La journée a déjà été clôturée pour cette boutique");
        }

        // Step 2: The closure covers the sales made since the previous closure, plus the
        // sales made before it but received after it (offline devices syncing late), so
        // that every sale belongs to exactly one closure (see ClosureWindow).
        // One definition shared with the « Fin de journée » PDF (v1s-18-3); received now.
        ClosureWindow window = ClosureWindow.forClosure(closedAt, now, previous);
        List<Sale> sales = saleRepository.findForClosure(command.storeId(), window);

        // Step 3: Build summary
        DayClosureSummary summary = new DayClosureSummaryBuilder()
                .addSales(sales)
                .build();

        // Step 4: Persist closure
        DayClosure closure = new DayClosure(
                command.closureId(),
                command.storeId(),
                command.actorId(),
                closedAt,
                summary,
                command.isAutomatic(),
                command.tenantId(),
                now
        );
        dayClosureRepository.save(closure);

        // Step 5: Publish event with the closure window
        DayClosedEvent event = new DayClosedEvent(
                closure.getId(),
                closure.getStoreId(),
                closure.getActorId(),
                summary,
                command.isAutomatic(),
                command.tenantId(),
                closedAt,
                window.start(),
                window.end(),
                window.lateFrom(),
                window.receivedUntil()
        );
        eventPublisher.publishEvent(event);

        return closure;
    }
}
