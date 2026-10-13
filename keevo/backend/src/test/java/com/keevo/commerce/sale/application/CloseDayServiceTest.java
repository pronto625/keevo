package com.keevo.commerce.sale.application;

import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.commerce.sale.application.service.CloseDayService;
import com.keevo.commerce.sale.application.service.DayClosureSummaryBuilder;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TDD RED tests for CloseDayService.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
@ExtendWith(MockitoExtension.class)
class CloseDayServiceTest {

    @Mock
    private SaleRepository saleRepository;

    @Mock
    private DayClosureRepository dayClosureRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TransactionLockPort transactionLock;

    private CloseDayService service;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        service = new CloseDayService(saleRepository, dayClosureRepository, eventPublisher, transactionLock,
                Clock.systemUTC());
    }

    @Test
    void closeDayService_givenSalesForDay_computesCorrectSummary() {
        // Given - 3 COMPLETED sales today
        List<Sale> salesForDay = List.of(
                createSale(15000, 0, PaymentMode.CASH, SaleStatus.COMPLETED),
                createSale(20000, 0, PaymentMode.MOBILE_MONEY, SaleStatus.COMPLETED),
                createSale(10000, 0, PaymentMode.CASH, SaleStatus.COMPLETED)
        );

        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(salesForDay);

        // When
        var result = service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false));

        // Then
        assertThat(result.getSummary().totalSales()).isEqualTo(3);
        assertThat(result.getSummary().totalRevenue()).isEqualTo(45000); // 15000 + 20000 + 10000
        assertThat(result.getSummary().cashAmount()).isEqualTo(25000);   // 15000 + 10000
        assertThat(result.getSummary().momoAmount()).isEqualTo(20000);
    }

    @Test
    void closeDayService_excludesPendingValidationFromRevenue() {
        // Given - 2 COMPLETED + 1 PENDING_VALIDATION
        List<Sale> allSalesForDay = List.of(
                createSale(15000, 0, PaymentMode.CASH, SaleStatus.COMPLETED),
                createSale(20000, 0, PaymentMode.MOBILE_MONEY, SaleStatus.COMPLETED),
                createSale(10000, 0, PaymentMode.CASH, SaleStatus.PENDING_VALIDATION) // excluded from revenue
        );

        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(allSalesForDay);

        // When
        var result = service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false));

        // Then - PENDING_VALIDATION excluded from totalRevenue but counted in pendingSales
        assertThat(result.getSummary().totalSales()).isEqualTo(2);           // only COMPLETED
        assertThat(result.getSummary().totalRevenue()).isEqualTo(35000);     // 15000 + 20000 (no 10000 pending)
        assertThat(result.getSummary().pendingSalesCount()).isEqualTo(1);
        assertThat(result.getSummary().pendingSalesTotal()).isEqualTo(10000);
    }

    @Test
    void closeDayService_publishesDayClosedEvent() {
        // Given
        List<Sale> salesForDay = List.of(
                createSale(15000, 0, PaymentMode.CASH, SaleStatus.COMPLETED)
        );

        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(salesForDay);

        // When
        service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false));

        // Then
        var eventCaptor = ArgumentCaptor.forClass(DayClosedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        DayClosedEvent event = eventCaptor.getValue();
        assertThat(event.storeId()).isEqualTo(STORE_ID);
        assertThat(event.actorId()).isEqualTo(ACTOR_ID);
        assertThat(event.tenantId()).isEqualTo(TENANT_ID);
        assertThat(event.isAutomatic()).isFalse();
        assertThat(event.summary().totalSales()).isEqualTo(1);
    }

    // Story 13.9: auto-close vs manual/sync close of the same store — the per-store advisory
    // lock is taken before any exists-check, so the loser sees the winner's committed closure.
    @Test
    void closeDay_locksStoreBeforeExistsChecks_thenSaves() {
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(List.of());

        service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, true));

        InOrder inOrder = inOrder(transactionLock, dayClosureRepository);
        inOrder.verify(transactionLock).lock("day-closure:" + STORE_ID);
        inOrder.verify(dayClosureRepository).findLastClosureForStore(STORE_ID);
        inOrder.verify(dayClosureRepository).existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class));
        inOrder.verify(dayClosureRepository).save(any());
    }

    @Test
    void closeDay_concurrentLoser_seesCommittedClosure_dayAlreadyClosed() {
        // The winner committed while the loser waited on the lock: the loser's exists-check
        // (after the lock) now returns true.
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, true)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.DAY_ALREADY_CLOSED);
        InOrder inOrder = inOrder(transactionLock, dayClosureRepository);
        inOrder.verify(transactionLock).lock("day-closure:" + STORE_ID);
        inOrder.verify(dayClosureRepository).existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class));
        verify(dayClosureRepository, never()).save(any());
    }

    @Test
    void closeDayService_alreadyClosed_throwsDomainException() {
        // Given - closure already exists for today
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.DAY_ALREADY_CLOSED);
    }

    @Test
    void closeDayService_savesClosureToRepository() {
        // Given
        List<Sale> salesForDay = List.of(
                createSale(10000, 0, PaymentMode.CASH, SaleStatus.COMPLETED)
        );

        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(salesForDay);

        // When
        service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false));

        // Then
        var closureCaptor = ArgumentCaptor.forClass(DayClosure.class);
        verify(dayClosureRepository).save(closureCaptor.capture());

        DayClosure saved = closureCaptor.getValue();
        assertThat(saved.getStoreId()).isEqualTo(STORE_ID);
        assertThat(saved.getActorId()).isEqualTo(ACTOR_ID);
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
    }

    // ── Closure window: (previous closure, this closure] ─────────────────────

    private DayClosedEvent closeAndCaptureEvent(CloseDayCommand command) {
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(List.of());
        service.closeDay(command);
        var eventCaptor = ArgumentCaptor.forClass(DayClosedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private DayClosure previousClosure(Instant closedAt) {
        return previousClosure(closedAt, closedAt);
    }

    private DayClosure previousClosure(Instant closedAt, Instant receivedAt) {
        return new DayClosure(UUID.randomUUID(), STORE_ID, ACTOR_ID, closedAt, null, true, TENANT_ID,
                receivedAt);
    }

    @Test
    void window_noPreviousClosure_startsAtMidnightAndEndsAtClosure() {
        ZoneId wat = ZoneId.of("Africa/Lagos");
        Instant before = Instant.now();
        when(dayClosureRepository.findLastClosureForStore(STORE_ID)).thenReturn(java.util.Optional.empty());

        DayClosedEvent event = closeAndCaptureEvent(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false));

        assertThat(event.windowStart()).isEqualTo(LocalDate.now(wat).atStartOfDay(wat).toInstant());
        assertThat(event.windowEnd()).isEqualTo(event.occurredAt());
        assertThat(event.windowEnd()).isBetween(before, Instant.now());
    }

    // Sales made after an automatic 20:00 closure belong to the next closure, and so do sales
    // made before the previous closure but received after it (offline devices syncing late).
    @Test
    void window_startsRightAfterPreviousClosure_andTakesLateSales() {
        Instant previous = Instant.now().minus(java.time.Duration.ofHours(20));
        Instant previousReceived = previous.plusSeconds(30);
        when(dayClosureRepository.findLastClosureForStore(STORE_ID))
                .thenReturn(java.util.Optional.of(previousClosure(previous, previousReceived)));

        DayClosedEvent event = closeAndCaptureEvent(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, true));

        assertThat(event.windowStart()).isAfter(previous);
        assertThat(java.time.Duration.between(previous, event.windowStart()).toMillis()).isLessThan(1);
        assertThat(event.lateFrom()).isEqualTo(previousReceived);
        // v1s-13-13: received until now − commit-safety margin.
        assertThat(event.receivedUntil()).isBetween(
                event.windowEnd().minus(ClosureWindow.COMMIT_SAFETY_MARGIN),
                Instant.now().minus(ClosureWindow.COMMIT_SAFETY_MARGIN));
        verify(saleRepository).findForClosure(STORE_ID, event.window());
    }

    // v1s-18-3 — the « Fin de journée » PDF recomputes a closure's window afterwards with
    // ClosureWindow.forClosure(closure, previous): it must be the window used at closing time.
    @Test
    void window_equalsForClosureOfTheSavedClosure_withAndWithoutPrevious() {
        Instant prevAt = Instant.now().minus(java.time.Duration.ofHours(20));
        DayClosure prev = previousClosure(prevAt, prevAt.plusSeconds(30));
        for (java.util.Optional<DayClosure> previous : List.of(java.util.Optional.<DayClosure>empty(),
                java.util.Optional.of(prev))) {
            reset(dayClosureRepository, eventPublisher, saleRepository);
            when(dayClosureRepository.findLastClosureForStore(STORE_ID)).thenReturn(previous);

            DayClosedEvent event = closeAndCaptureEvent(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false));

            var saved = ArgumentCaptor.forClass(DayClosure.class);
            verify(dayClosureRepository).save(saved.capture());
            assertThat(ClosureWindow.forClosure(saved.getValue(), previous)).isEqualTo(event.window());
        }
    }

    // Closures of a store only move forward: an older offline closure pushed after a newer
    // one would count the newer closure's sales a second time.
    @Test
    void closureOlderThanLastClosure_isRefusedAsAlreadyClosed() {
        ZoneId wat = ZoneId.of("Africa/Lagos");
        Instant closedAt = LocalDate.now(wat).minusDays(1).atTime(21, 0).atZone(wat).toInstant();
        when(dayClosureRepository.findLastClosureForStore(STORE_ID))
                .thenReturn(java.util.Optional.of(previousClosure(Instant.now().minusSeconds(60))));

        assertThatThrownBy(() -> service.closeDay(
                new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false, null, closedAt)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.DAY_ALREADY_CLOSED);
        verify(dayClosureRepository, never()).save(any());
    }

    @Test
    void retriedPushWithExistingClosureId_isAlreadyClosed() {
        UUID closureId = UUID.randomUUID();
        when(dayClosureRepository.existsById(closureId)).thenReturn(true);

        assertThatThrownBy(() -> service.closeDay(
                new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false, closureId, null)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.DAY_ALREADY_CLOSED);
        verify(dayClosureRepository, never()).save(any());
    }

    @Test
    void closureOlderThanSevenDays_isRejected() {
        Instant closedAt = Instant.now().minus(CloseDayService.MAX_BACKDATE).minusSeconds(60);

        assertThatThrownBy(() -> service.closeDay(
                new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false, null, closedAt)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(saleRepository);
    }

    // ── Offline closure: the device's id and the day it was made ─────────────

    @Test
    void offlineClosure_keepsClientIdAndClosesTheDayItWasMade() {
        ZoneId wat = ZoneId.of("Africa/Lagos");
        LocalDate yesterday = LocalDate.now(wat).minusDays(1);
        Instant closedAt = yesterday.atTime(21, 0).atZone(wat).toInstant();
        UUID closureId = UUID.randomUUID();
        when(dayClosureRepository.existsByStoreIdAndDate(STORE_ID, yesterday)).thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(List.of());

        var result = service.closeDay(
                new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false, closureId, closedAt));

        assertThat(result.getId()).isEqualTo(closureId);
        assertThat(result.getClosedAt()).isEqualTo(closedAt);
        verify(dayClosureRepository).existsByStoreIdAndDate(STORE_ID, yesterday);
    }

    @Test
    void offlineClosure_inTheFuture_isClampedToNow() {
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(List.of());

        var result = service.closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false,
                null, Instant.now().plus(java.time.Duration.ofDays(2))));

        assertThat(result.getClosedAt()).isBeforeOrEqualTo(Instant.now());
    }

    // ── Story 13.11: injected Clock, evening sales (PO decision D1) ──────────

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    /** Fixed "now": Wednesday 2026-10-14 20:00 WAT. */
    private static final Instant FIXED_NOW =
            LocalDate.of(2026, 10, 14).atTime(20, 0).atZone(WAT).toInstant();

    private CloseDayService fixedClockService() {
        return new CloseDayService(saleRepository, dayClosureRepository, eventPublisher, transactionLock,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void fixedClock_closedAtAndReceivedAtComeFromTheClock() {
        when(dayClosureRepository.existsByStoreIdAndDate(STORE_ID, LocalDate.of(2026, 10, 14)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(List.of());

        var result = fixedClockService().closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, true));

        assertThat(result.getClosedAt()).isEqualTo(FIXED_NOW);
        // v1s-13-13: the persisted arrival anchor is now − 5 s (commit-safety margin).
        assertThat(result.getReceivedAt()).isEqualTo(FIXED_NOW.minusSeconds(5));
        verify(dayClosureRepository).existsByStoreIdAndDate(STORE_ID, LocalDate.of(2026, 10, 14));
    }

    // v1s-13-13 — commit lag: a sale stamped at T ≤ now but committed after this closure's
    // queries is not covered here when T > now − 5 s, and is covered by the next closure
    // (lateFrom = this closure's persisted receivedAt) — exactly once.
    @Test
    void commitLag_saleStampedJustBeforeClosure_landsInTheNextClosureOnly() {
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(List.of());

        DayClosure closure = fixedClockService().closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, true));
        var eventCaptor = ArgumentCaptor.forClass(DayClosedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ClosureWindow window = eventCaptor.getValue().window();
        assertThat(window.receivedUntil()).isEqualTo(FIXED_NOW.minusSeconds(5));

        Instant lagging = FIXED_NOW.minusSeconds(1);
        assertThat(window.covers(lagging, lagging)).isFalse();
        ClosureWindow next = ClosureWindow.forClosure(
                FIXED_NOW.plus(java.time.Duration.ofDays(1)),
                ClosureWindow.receivedLimit(FIXED_NOW.plus(java.time.Duration.ofDays(1))),
                java.util.Optional.of(closure));
        assertThat(next.covers(lagging, lagging)).isTrue();
        // A sale received before the margin stays in this closure only.
        Instant onTime = FIXED_NOW.minusSeconds(6);
        assertThat(window.covers(onTime, onTime)).isTrue();
        assertThat(next.covers(onTime, onTime)).isFalse();
    }

    @Test
    void fixedClock_futureOfflineClosure_isClampedToClockNow() {
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(List.of());

        var result = fixedClockService().closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false,
                null, FIXED_NOW.plusSeconds(3600)));

        assertThat(result.getClosedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void fixedClock_sevenDayGate_usesTheClock() {
        // Exactly 7 days before the clock's now: accepted (gate is "strictly older than").
        Instant sevenDaysAgo = FIXED_NOW.minus(CloseDayService.MAX_BACKDATE);
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(List.of());

        var accepted = fixedClockService().closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, false,
                null, sevenDaysAgo));
        assertThat(accepted.getClosedAt()).isEqualTo(sevenDaysAgo);

        // One second older than that: refused, relative to the clock (not the system time).
        assertThatThrownBy(() -> fixedClockService().closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID,
                TENANT_ID, false, null, sevenDaysAgo.minusSeconds(1))))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // D1: a sale made after the evening auto-closure (D 20:00) is not excluded: it belongs
    // to the next closure (D+1 20:00), and to that one only.
    @Test
    void eveningSaleAfterClosure_isCoveredByTheNextClosure() {
        Instant previousClosedAt = FIXED_NOW.minus(java.time.Duration.ofDays(1)); // D 20:00
        when(dayClosureRepository.findLastClosureForStore(STORE_ID))
                .thenReturn(java.util.Optional.of(previousClosure(previousClosedAt)));
        when(dayClosureRepository.existsByStoreIdAndDate(eq(STORE_ID), any(LocalDate.class)))
                .thenReturn(false);
        when(saleRepository.findForClosure(eq(STORE_ID), any())).thenReturn(List.of());

        fixedClockService().closeDay(new CloseDayCommand(STORE_ID, ACTOR_ID, TENANT_ID, true)); // D+1 20:00

        var eventCaptor = ArgumentCaptor.forClass(DayClosedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ClosureWindow window = eventCaptor.getValue().window();
        ClosureWindow previousWindow = new ClosureWindow(
                previousClosedAt.minus(java.time.Duration.ofDays(1)), previousClosedAt, null, previousClosedAt);

        for (int[] hm : new int[][]{{20, 1}, {21, 30}, {23, 59}}) {
            Instant evening = previousClosedAt.atZone(WAT).toLocalDate().atTime(hm[0], hm[1])
                    .atZone(WAT).toInstant();
            assertThat(window.covers(evening, evening)).as("sale at %s", evening).isTrue();
            assertThat(previousWindow.covers(evening, evening)).isFalse();
        }
        // A sale made right at the previous closure stays in the previous one.
        assertThat(window.covers(previousClosedAt, previousClosedAt)).isFalse();
        assertThat(window.end()).isEqualTo(FIXED_NOW);
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private Sale createSale(int totalAmount, int discount, PaymentMode paymentMode, SaleStatus status) {
        UUID saleId = UUID.randomUUID();
        var item = new SaleItem(UUID.randomUUID(), saleId, UUID.randomUUID(), null,
                "Produit", totalAmount, totalAmount, 1);
        return new Sale(saleId, STORE_ID, ACTOR_ID, null,
                paymentMode, totalAmount - discount, discount, status,
                Instant.now(), Instant.now(), List.of(item));
    }
}
