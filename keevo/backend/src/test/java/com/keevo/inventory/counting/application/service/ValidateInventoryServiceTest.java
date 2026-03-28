package com.keevo.inventory.counting.application.service;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.inventory.counting.domain.event.InventoryValidatedEvent;
import com.keevo.inventory.counting.domain.model.*;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryCommand;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ValidateInventoryServiceTest {

    @Mock InventorySessionRepository sessionRepo;
    @Mock InventoryCountRepository countRepo;
    @Mock StockOperationService stockOperationService;
    @Mock StockLevelRepository stockLevelRepo;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks ValidateInventoryService service;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private InventorySession inProgressSession() {
        return new InventorySession(
                sessionId, storeId, InventoryScope.FULL, null,
                InventorySessionStatus.IN_PROGRESS, actorId, Instant.now(),
                null, null, null, Instant.now());
    }

    private InventorySession validatedSession() {
        return new InventorySession(
                sessionId, storeId, InventoryScope.FULL, null,
                InventorySessionStatus.VALIDATED, actorId, Instant.now(),
                null, null, Instant.now(), Instant.now());
    }

    private InventorySession cancelledSession() {
        return new InventorySession(
                sessionId, storeId, InventoryScope.FULL, null,
                InventorySessionStatus.CANCELLED, actorId, Instant.now(),
                actorId, Instant.now(), null, Instant.now());
    }

    private InventoryCount count(UUID productId, UUID variantId, int theoretical, Integer physical) {
        return new InventoryCount(
                UUID.randomUUID(), sessionId, productId, variantId,
                "Product", null, theoretical, physical,
                physical != null ? Instant.now() : null,
                physical != null ? actorId : null,
                Instant.now());
    }

    private StockLevel stockLevel(UUID productId, UUID storeId, int quantity) {
        return new StockLevel(UUID.randomUUID(), productId, null, storeId, quantity, Instant.now());
    }

    // ── Happy path ──────────────────────────────────────────────────

    @Test
    void execute_happyPath_shouldApplyAdjustmentsAndValidateSession() {
        // Given: IN_PROGRESS session, 3 counts (1 concordant, 1 surplus, 1 shortage)
        InventorySession session = inProgressSession();
        UUID prodA = UUID.randomUUID(); // concordant: theoretical=10, physical=10
        UUID prodB = UUID.randomUUID(); // surplus: theoretical=5, physical=8
        UUID prodC = UUID.randomUUID(); // shortage: theoretical=10, physical=7

        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(countRepo.findBySessionId(sessionId)).thenReturn(List.of(
                count(prodA, null, 10, 10),
                count(prodB, null, 5, 8),
                count(prodC, null, 10, 7)
        ));
        // Live stock levels (only for products with écart ≠ 0)
        when(stockLevelRepo.findByProductAndStore(prodB, storeId)).thenReturn(Optional.of(stockLevel(prodB, storeId, 5)));
        when(stockLevelRepo.findByProductAndStore(prodC, storeId)).thenReturn(Optional.of(stockLevel(prodC, storeId, 10)));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        ValidateInventoryResult result = service.execute(new ValidateInventoryCommand(sessionId, actorId));

        // Then: 2 adjustments (prodB +3, prodC -3), skip prodA (concordant, delta=0)
        assertThat(result.adjustmentsApplied()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(InventorySessionStatus.VALIDATED);
        assertThat(result.completedAt()).isNotNull();

        // recordOperation called exactly 2 times
        verify(stockOperationService, times(2)).recordOperation(
                any(), isNull(), eq(storeId), eq(MovementType.ADJUSTMENT), anyInt(), eq(actorId), anyString());

        // Verify prodB: delta = 8 - 5 = +3
        verify(stockOperationService).recordOperation(prodB, null, storeId, MovementType.ADJUSTMENT, 3, actorId, "INVENTORY:" + sessionId);
        // Verify prodC: delta = 7 - 10 = -3
        verify(stockOperationService).recordOperation(prodC, null, storeId, MovementType.ADJUSTMENT, -3, actorId, "INVENTORY:" + sessionId);

        verify(sessionRepo).save(session);
        verify(eventPublisher).publishEvent(any(InventoryValidatedEvent.class));
    }

    // ── Error cases ─────────────────────────────────────────────────

    @Test
    void execute_sessionNotFound_shouldThrow() {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(new ValidateInventoryCommand(sessionId, actorId)))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVENTORY_SESSION_NOT_FOUND));
    }

    @Test
    void execute_sessionAlreadyValidated_shouldThrow() {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(validatedSession()));

        assertThatThrownBy(() -> service.execute(new ValidateInventoryCommand(sessionId, actorId)))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS));
    }

    @Test
    void execute_sessionCancelled_shouldThrow() {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(cancelledSession()));

        assertThatThrownBy(() -> service.execute(new ValidateInventoryCommand(sessionId, actorId)))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS));
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Test
    void execute_allConcordant_shouldValidateWithZeroAdjustments() {
        InventorySession session = inProgressSession();
        UUID prodA = UUID.randomUUID();

        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(countRepo.findBySessionId(sessionId)).thenReturn(List.of(
                count(prodA, null, 10, 10)
        ));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ValidateInventoryResult result = service.execute(new ValidateInventoryCommand(sessionId, actorId));

        assertThat(result.adjustmentsApplied()).isEqualTo(0);
        assertThat(result.status()).isEqualTo(InventorySessionStatus.VALIDATED);
        verify(stockOperationService, never()).recordOperation(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void execute_uncountedProducts_shouldSkip() {
        InventorySession session = inProgressSession();
        UUID prodA = UUID.randomUUID(); // uncounted (physical == null)
        UUID prodB = UUID.randomUUID(); // counted, gap != 0

        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(countRepo.findBySessionId(sessionId)).thenReturn(List.of(
                count(prodA, null, 10, null),
                count(prodB, null, 5, 8)
        ));
        when(stockLevelRepo.findByProductAndStore(prodB, storeId)).thenReturn(Optional.of(stockLevel(prodB, storeId, 5)));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ValidateInventoryResult result = service.execute(new ValidateInventoryCommand(sessionId, actorId));

        assertThat(result.adjustmentsApplied()).isEqualTo(1);
        // Only prodB adjusted
        verify(stockOperationService, times(1)).recordOperation(
                eq(prodB), isNull(), eq(storeId), eq(MovementType.ADJUSTMENT), eq(3), eq(actorId), anyString());
    }

    @Test
    void execute_recordOperationCalledWithCorrectParameters() {
        InventorySession session = inProgressSession();
        UUID prodA = UUID.randomUUID();

        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(countRepo.findBySessionId(sessionId)).thenReturn(List.of(
                count(prodA, null, 10, 7) // theoretical=10, physical=7
        ));
        // Live stock is 10 (same as theoretical)
        when(stockLevelRepo.findByProductAndStore(prodA, storeId)).thenReturn(Optional.of(stockLevel(prodA, storeId, 10)));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new ValidateInventoryCommand(sessionId, actorId));

        // delta = physical(7) - currentStock(10) = -3
        verify(stockOperationService).recordOperation(
                prodA, null, storeId, MovementType.ADJUSTMENT, -3, actorId, "INVENTORY:" + sessionId);
    }

    @Test
    void execute_publishesInventoryValidatedEvent() {
        InventorySession session = inProgressSession();
        UUID prodA = UUID.randomUUID();
        UUID prodB = UUID.randomUUID();

        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(countRepo.findBySessionId(sessionId)).thenReturn(List.of(
                count(prodA, null, 10, 8),
                count(prodB, null, 5, 8)
        ));
        when(stockLevelRepo.findByProductAndStore(prodA, storeId)).thenReturn(Optional.of(stockLevel(prodA, storeId, 10)));
        when(stockLevelRepo.findByProductAndStore(prodB, storeId)).thenReturn(Optional.of(stockLevel(prodB, storeId, 5)));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new ValidateInventoryCommand(sessionId, actorId));

        ArgumentCaptor<InventoryValidatedEvent> captor = ArgumentCaptor.forClass(InventoryValidatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        InventoryValidatedEvent event = captor.getValue();
        assertThat(event.sessionId()).isEqualTo(sessionId);
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.adjustmentCount()).isEqualTo(2);
        assertThat(event.tenantId()).isEqualTo("kv_test");
    }
}
