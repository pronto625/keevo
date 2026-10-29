package com.keevo.commerce.sale.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.inventory.api.StockMovementKind;
import com.keevo.inventory.api.StockQueryPort;
import com.keevo.inventory.api.StockCommandPort;
import com.keevo.commerce.sale.application.service.ValidateSaleService;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.event.*;
import com.keevo.commerce.api.*;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase.CancelPendingSaleCommand;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase.ValidateSaleCommand;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ValidateSaleServiceTest {

    @Mock private SaleRepository saleRepository;
    @Mock private StockQueryPort stockQuery;
    @Mock private StockCommandPort stockCommand;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ValidateSaleService service;

    private static final UUID SALE_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final UUID PRODUCT_B = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("kv_test");
        service = new ValidateSaleService(
                saleRepository, stockQuery, stockCommand, eventPublisher,
                new ObjectMapper());
        // Story 13.9: PENDING_VALIDATION transitions are conditional claims; default = won.
        lenient().when(saleRepository.updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED))
                .thenReturn(true);
        lenient().when(saleRepository.updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.CANCELLED))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Sale pendingSale() {
        var items = List.of(
                new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_A, null, "Produit A", 3000, 3000, 2),
                new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_B, null, "Produit B", 5000, 5000, 1));
        return new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, 11000, 0, SaleStatus.PENDING_VALIDATION, NOW, NOW, items);
    }

    private Sale completedSale() {
        return completedSale(0L);
    }

    private Sale completedSale(long version) {
        var items = List.of(
                new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_A, null, "Produit A", 3000, 3000, 1));
        return new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, 3000, 0, SaleStatus.COMPLETED, NOW, NOW, items, version);
    }


    // ── Manual validation (AC9) ──────────────────────────────────

    @Test
    void manualValidate_withJustification_transitionsToCompleted() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_A, null, STORE_ID)).thenReturn(20);
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_B, null, STORE_ID)).thenReturn(10);

        service.validateSale(new ValidateSaleCommand(SALE_ID, ACTOR_ID,
                "Client fidèle, produit confirmé par fournisseur"));

        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED);
        verify(stockCommand, times(2)).recordMovement(any(), argThat(m -> java.util.Objects.equals(m.storeId(), STORE_ID)
                && java.util.Objects.equals(m.actorId(), ACTOR_ID)
                && !m.allowNegative()));

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof SaleManuallyValidatedEvent);
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof SaleCompletedEvent);
        // v1s-18-6: the audit snapshot carries the real items, not a hard-coded "[]"
        SaleCompletedEvent completed = eventCaptor.getAllValues().stream()
                .filter(SaleCompletedEvent.class::isInstance).map(SaleCompletedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(completed.getItemsSnapshot())
                .contains(PRODUCT_A.toString())
                .contains(PRODUCT_B.toString());
    }

    @Test
    void manualValidate_insufficientStock_forcesToZero_emitsStockForcedZeroEvent() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));
        // Product A: have 1, need 2 → force to 0
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_A, null, STORE_ID)).thenReturn(1);
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_B, null, STORE_ID)).thenReturn(10);

        service.validateSale(new ValidateSaleCommand(SALE_ID, ACTOR_ID,
                "Validation forcée malgré stock insuffisant"));

        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED);

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof StockForcedZeroEvent);
    }

    // 12.2c: availability is read on the level that is decremented — the item's variant — as
    // RecordSaleService does (previously the no-variant level was read, then the variant decremented).
    private Sale pendingVariantSale(UUID variantId, UUID itemId) {
        var items = List.of(new SaleItem(itemId, SALE_ID, PRODUCT_A, variantId, "Produit A — Rouge", 3000, 3000, 1));
        return new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, 3000, 0, SaleStatus.PENDING_VALIDATION, NOW, NOW, items);
    }

    @Test
    void manualValidate_variantItem_checksAndDecrementsTheVariantLevel() {
        UUID variantId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingVariantSale(variantId, itemId)));
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_A, variantId, STORE_ID)).thenReturn(1);
        lenient().when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_A, null, STORE_ID)).thenReturn(0);

        service.validateSale(new ValidateSaleCommand(SALE_ID, ACTOR_ID, null));

        verify(stockQuery, never()).availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_A, null, STORE_ID);
        verify(stockCommand).recordMovement(any(), argThat(m -> m.kind() == StockMovementKind.SALE
                && PRODUCT_A.equals(m.productId()) && variantId.equals(m.variantId())
                && m.quantityChange() == -1 && itemId.equals(m.movementId()) && !m.allowNegative()));
        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).noneMatch(e -> e instanceof StockForcedZeroEvent);
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof SaleCompletedEvent);
    }

    @Test
    void manualValidate_variantItemWithoutVariantStock_isForcedToZeroWithoutDecrement() {
        UUID variantId = UUID.randomUUID();
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingVariantSale(variantId, UUID.randomUUID())));
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_A, variantId, STORE_ID)).thenReturn(0);
        lenient().when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_A, null, STORE_ID)).thenReturn(5);

        service.validateSale(new ValidateSaleCommand(SALE_ID, ACTOR_ID, null));

        // Product-level stock 5 used to allow a -1 on the empty variant (INSUFFICIENT_STOCK
        // from the inventory); now the variant level decides: forced to zero, nothing decremented.
        verify(stockCommand, never()).recordMovement(any(), any());
        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e instanceof StockForcedZeroEvent f
                && f.availableQuantity() == 0 && f.requestedQuantity() == 1);
    }

    @Test
    void manualValidate_withoutJustification_succeeds() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_A, null, STORE_ID)).thenReturn(20);
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_B, null, STORE_ID)).thenReturn(10);

        service.validateSale(new ValidateSaleCommand(SALE_ID, ACTOR_ID, null));

        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED);
    }

    @Test
    void manualValidate_shortJustification_throws() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));

        assertThatThrownBy(() -> service.validateSale(
                new ValidateSaleCommand(SALE_ID, ACTOR_ID, "trop crt")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void manualValidate_alreadyCompleted_throws() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale()));

        assertThatThrownBy(() -> service.validateSale(
                new ValidateSaleCommand(SALE_ID, ACTOR_ID, "Trying to validate completed sale")))
                .isInstanceOf(DomainException.class);
    }

    // ── Cancellation (AC10) ──────────────────────────────────────

    @Test
    void cancelPendingSale_withJustification_transitionsToCancelled() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));

        service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null,
                "Produit finalement non disponible chez le fournisseur"));

        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.CANCELLED);

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(SaleCancelledEvent.class);
        // v1s-18-6: the pending-cancel snapshot carries the sale's items, not "[]"
        var cancelled = (SaleCancelledEvent) eventCaptor.getValue();
        assertThat(cancelled.itemsSnapshot())
                .contains(PRODUCT_A.toString())
                .contains(PRODUCT_B.toString());
        assertThat(cancelled.storeId()).isEqualTo(STORE_ID);
    }

    @Test
    void cancelPendingSale_noStockRestoration() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));

        service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null,
                "Stock jamais décrémenté en PENDING"));

        verifyNoInteractions(stockCommand);
    }

    @Test
    void cancelPendingSale_withoutJustification_succeeds() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));

        service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null, null));

        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.CANCELLED);
    }

    // ── Story v1s-13-5 — Cancellation of COMPLETED sales (AC1-AC3) ────────────

    @Test
    void cancelPendingSale_completedSale_restoresStockAndCancels() {
        Sale sale = completedSale(3L);
        UUID itemId = sale.getItems().get(0).getId();
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(sale));
        when(saleRepository.updateStatusIfCurrent(SALE_ID, SaleStatus.COMPLETED, 3L, SaleStatus.CANCELLED))
                .thenReturn(true);

        service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null,
                "Erreur de scan, article rendu au client"));

        // Story v1s-13-10: the COMPLETED→CANCELLED claim checks the version that was read.
        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.COMPLETED, 3L, SaleStatus.CANCELLED);
        verify(saleRepository, never()).updateStatusIfCurrent(SALE_ID, SaleStatus.COMPLETED, SaleStatus.CANCELLED);
        // completedSale() has exactly 1 item, quantity=1 → recordOperation called once,
        // delta must be POSITIVE (restoration), not negative.
        // v1s-13-13: deterministic movement id (same on the device) → one history row after pull.
        verify(stockCommand, times(1)).recordMovement(any(), argThat(m -> java.util.Objects.equals(m.productId(), PRODUCT_A)
                && java.util.Objects.equals(m.storeId(), STORE_ID)
                && java.util.Objects.equals(m.kind(), StockMovementKind.SALE_CANCELLED)
                && m.quantityChange() == 1
                && java.util.Objects.equals(m.actorId(), ACTOR_ID)
                && java.util.Objects.equals(m.movementId(), com.keevo.shared.domain.DeterministicIds.saleCancelledMovementId(itemId))
                && !m.allowNegative()));

        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(SaleCancelledEvent.class);
        var event = (SaleCancelledEvent) eventCaptor.getValue();
        assertThat(event.itemsSnapshot()).isNotNull().isNotBlank();
    }

    @Test
    void cancelPendingSale_alreadyCancelled_throwsSaleAlreadyCancelled() {
        var cancelled = new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, 3000, 0, SaleStatus.CANCELLED, NOW, NOW,
                List.of(new SaleItem(UUID.randomUUID(), SALE_ID, PRODUCT_A, null, "Produit A", 3000, 3000, 1)));
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.cancelPendingSale(
                new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null, "Nouvelle tentative d'annulation")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("already cancelled");

        verify(saleRepository, never()).updateStatusIfCurrent(any(), any(), any());
        verify(saleRepository, never()).updateStatusIfCurrent(any(), any(), anyLong(), any());
        verifyNoInteractions(stockCommand);
    }

    @Test
    void cancelPendingSale_completedSale_blankJustification_throwsJustificationRequired() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale()));

        assertThatThrownBy(() -> service.cancelPendingSale(
                new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null, "   ")))
                .isInstanceOf(DomainException.class);

        verify(saleRepository, never()).updateStatusIfCurrent(any(), any(), anyLong(), any());
        verifyNoInteractions(stockCommand);
    }

    @Test
    void cancelPendingSale_completedSale_nullJustification_throwsJustificationRequired() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale()));

        assertThatThrownBy(() -> service.cancelPendingSale(
                new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null, null)))
                .isInstanceOf(DomainException.class);

        verifyNoInteractions(stockCommand);
    }

    @Test
    void cancelPendingSale_completedSale_shortJustification_throwsJustificationTooShort() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale()));

        assertThatThrownBy(() -> service.cancelPendingSale(
                new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null, "trop crt")))
                .isInstanceOf(DomainException.class);

        verify(saleRepository, never()).updateStatusIfCurrent(any(), any(), anyLong(), any());
        verifyNoInteractions(stockCommand);
    }

    // ── Story 12.6 — Employee store scope (FR36) ──────────────────────

    @Test
    void validateSale_employeeOwnStore_succeeds() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_A, null, STORE_ID)).thenReturn(20);
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_B, null, STORE_ID)).thenReturn(10);

        // employee assigned to the SAME store as the sale → succeeds
        service.validateSale(new ValidateSaleCommand(SALE_ID, ACTOR_ID, STORE_ID,
                "Validation par employé de sa boutique", null, null));

        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED);
    }

    @Test
    void validateSale_employeeOtherStore_throwsForbidden() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));
        UUID otherStoreId = UUID.randomUUID();

        // employee assigned to DIFFERENT store → FORBIDDEN
        assertThatThrownBy(() -> service.validateSale(
                new ValidateSaleCommand(SALE_ID, ACTOR_ID, otherStoreId,
                        "Tentative de valider vente d'une autre boutique", null, null)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Employee cannot operate pending sale outside assigned store");

        verify(saleRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void cancelPendingSale_employeeOwnStore_succeeds() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));

        // employee assigned to the SAME store as the sale → succeeds
        service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, STORE_ID,
                "Annulation par employé de sa boutique"));

        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.CANCELLED);
    }

    @Test
    void cancelPendingSale_employeeOtherStore_throwsForbidden() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));
        UUID otherStoreId = UUID.randomUUID();

        // employee assigned to DIFFERENT store → FORBIDDEN
        assertThatThrownBy(() -> service.cancelPendingSale(
                new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, otherStoreId,
                        "Tentative d'annuler vente d'une autre boutique")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Employee cannot operate pending sale outside assigned store");

        verify(saleRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    // Two concurrent cancellations both read COMPLETED: only the one whose conditional
    // COMPLETED→CANCELLED update changed the row may restore the stock.
    @Test
    void cancelPendingSale_completedSale_lostRace_doesNotRestoreStockTwice() {
        Sale cancelled = new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, 3000, 0, SaleStatus.CANCELLED, NOW, NOW, List.of(), 1L);
        when(saleRepository.findById(SALE_ID))
                .thenReturn(Optional.of(completedSale()))
                .thenReturn(Optional.of(cancelled));
        when(saleRepository.updateStatusIfCurrent(SALE_ID, SaleStatus.COMPLETED, 0L, SaleStatus.CANCELLED))
                .thenReturn(false);

        assertThatThrownBy(() -> service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null,
                "Erreur de scan, article rendu au client")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(com.keevo.shared.domain.exception.ErrorCode.SALE_ALREADY_CANCELLED);

        verifyNoInteractions(stockCommand);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── Story 13.9: cancel vs validate races, sync pendingOnly guard ─────────────

    private static final String JUSTIF = "Justification suffisamment longue";

    @Test
    void validateSale_lostRaceToConcurrentCancel_saleNotPending_noEventsPublished() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));
        when(saleRepository.updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED))
                .thenReturn(false);

        assertThatThrownBy(() -> service.validateSale(new ValidateSaleCommand(SALE_ID, ACTOR_ID, JUSTIF)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(com.keevo.shared.domain.exception.ErrorCode.SALE_NOT_PENDING);
        // The claim comes before any stock write or event: nothing to roll back, no alert.
        verifyNoInteractions(stockCommand);
        verify(eventPublisher, never()).publishEvent(any(StockForcedZeroEvent.class));
        verify(eventPublisher, never()).publishEvent(any(SaleCompletedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(SaleManuallyValidatedEvent.class));
    }

    @Test
    void cancelPending_lostRaceToConcurrentValidate_saleNotPending() {
        when(saleRepository.findById(SALE_ID))
                .thenReturn(Optional.of(pendingSale()))
                .thenReturn(Optional.of(completedSale()));
        when(saleRepository.updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.CANCELLED))
                .thenReturn(false);

        assertThatThrownBy(() -> service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null, JUSTIF)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(com.keevo.shared.domain.exception.ErrorCode.SALE_NOT_PENDING);
        verifyNoInteractions(stockCommand);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelPending_lostRaceToConcurrentCancel_saleAlreadyCancelled() {
        Sale cancelled = new Sale(SALE_ID, STORE_ID, ACTOR_ID, null,
                PaymentMode.CASH, 11000, 0, SaleStatus.CANCELLED, NOW, NOW, List.of());
        when(saleRepository.findById(SALE_ID))
                .thenReturn(Optional.of(pendingSale()))
                .thenReturn(Optional.of(cancelled));
        when(saleRepository.updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.CANCELLED))
                .thenReturn(false);

        assertThatThrownBy(() -> service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null, JUSTIF)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(com.keevo.shared.domain.exception.ErrorCode.SALE_ALREADY_CANCELLED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelPendingOnly_completedAtLoad_forbidden_noStockRestore() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(completedSale()));

        assertThatThrownBy(() -> service.cancelPendingSale(
                new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null, JUSTIF, true)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(com.keevo.shared.domain.exception.ErrorCode.FORBIDDEN);
        verify(saleRepository, never()).updateStatusIfCurrent(any(), any(), any());
        verify(saleRepository, never()).updateStatusIfCurrent(any(), any(), anyLong(), any());
        verifyNoInteractions(stockCommand);
    }

    @Test
    void cancelPendingOnly_validatedConcurrently_forbidden_noStockRestore() {
        when(saleRepository.findById(SALE_ID))
                .thenReturn(Optional.of(pendingSale()))
                .thenReturn(Optional.of(completedSale()));
        when(saleRepository.updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.CANCELLED))
                .thenReturn(false);

        assertThatThrownBy(() -> service.cancelPendingSale(
                new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null, JUSTIF, true)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(com.keevo.shared.domain.exception.ErrorCode.FORBIDDEN);
        verify(saleRepository, never()).updateStatusIfCurrent(any(), eq(SaleStatus.COMPLETED), anyLong(), any());
        verifyNoInteractions(stockCommand);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelPendingOnly_pendingSale_cancelled() {
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pendingSale()));

        service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null, JUSTIF, true));

        verify(saleRepository).updateStatusIfCurrent(SALE_ID, SaleStatus.PENDING_VALIDATION, SaleStatus.CANCELLED);
        verifyNoInteractions(stockCommand);
    }

    // ── Story v1s-13-10: optimistic lock on Sale + deterministic SALE movement ids ─────────

    // A correction committed between the cancel's read and its claim: status still COMPLETED but
    // version moved → the versioned claim updates 0 rows → 409 OPTIMISTIC_LOCK, no stock restore.
    @Test
    void cancelCompleted_lostClaimToConcurrentCorrection_throwsOptimisticLock_noStockRestore() {
        when(saleRepository.findById(SALE_ID))
                .thenReturn(Optional.of(completedSale(0L)))
                .thenReturn(Optional.of(completedSale(1L)));
        when(saleRepository.updateStatusIfCurrent(SALE_ID, SaleStatus.COMPLETED, 0L, SaleStatus.CANCELLED))
                .thenReturn(false);

        assertThatThrownBy(() -> service.cancelPendingSale(new CancelPendingSaleCommand(SALE_ID, ACTOR_ID, null,
                "Erreur de scan, article rendu au client")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(com.keevo.shared.domain.exception.ErrorCode.OPTIMISTIC_LOCK);

        verifyNoInteractions(stockCommand);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void validateSale_saleMovementIdsAreSaleItemIds() {
        Sale pending = pendingSale();
        when(saleRepository.findById(SALE_ID)).thenReturn(Optional.of(pending));
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_A, null, STORE_ID)).thenReturn(20);
        when(stockQuery.availableQuantity(TenantContext.getCurrentTenant(), PRODUCT_B, null, STORE_ID)).thenReturn(10);

        service.validateSale(new ValidateSaleCommand(SALE_ID, ACTOR_ID, JUSTIF));

        for (SaleItem item : pending.getItems()) {
            verify(stockCommand).recordMovement(any(), argThat(m -> java.util.Objects.equals(m.productId(), item.getProductId())
                && java.util.Objects.equals(m.storeId(), STORE_ID)
                && java.util.Objects.equals(m.kind(), StockMovementKind.SALE)
                && m.quantityChange() == -item.getQuantity()
                && java.util.Objects.equals(m.actorId(), ACTOR_ID)
                && java.util.Objects.equals(m.movementId(), item.getId())
                && !m.allowNegative()));
        }
    }
}
