package com.keevo.commerce.sale.application.service;

import com.keevo.inventory.api.StockCommandPort;
import com.keevo.inventory.api.StockMovementInput;
import com.keevo.inventory.api.StockMovementKind;
import com.keevo.inventory.api.StockQueryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.event.*;
import com.keevo.commerce.api.*;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.GetPendingSalesUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.domain.DeterministicIds;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ValidateSaleService — handles manual validation, cancellation, and listing of pending sales.
 * Story 4.3 AC6, AC9, AC10. Story v1s-13-5 extends cancellation to COMPLETED sales.
 *
 * <p><b>Note v1s-15-5 (2026-07-23) — currently unreachable from client:</b>
 * Since the 2026-05-03 draft-sale redesign (commit {@code 30bdd07}, Story v1s-13-6),
 * no Flutter client path produces {@code PENDING_VALIDATION} sales — the flow was replaced by
 * inline initial stock entry + auto-promotion. This service and {@link PendingSaleController}
 * are retained as a manual-rescue / admin tool for scenarios where {@code PENDING_VALIDATION}
 * sales could be reintroduced (e.g., CSV import, future administration API). Until then,
 * these code paths are tested and functional but exercised by zero production traffic.
 * See: {@code _bmad-output/implementation-artifacts/deferred-work.md} (v1s-15-5, Décision D1 — Option b).
 */
@Service
@Transactional
public class ValidateSaleService implements ValidateSaleUseCase, CancelPendingSaleUseCase, GetPendingSalesUseCase {

    private final SaleRepository saleRepository;
    private final StockQueryPort stockQuery;
    private final StockCommandPort stockCommand;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public ValidateSaleService(SaleRepository saleRepository,
                               StockQueryPort stockQuery,
                               StockCommandPort stockCommand,
                               ApplicationEventPublisher eventPublisher,
                               ObjectMapper objectMapper) {
        this.saleRepository = saleRepository;
        this.stockQuery = stockQuery;
        this.stockCommand = stockCommand;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void validateSale(ValidateSaleCommand command) {
        // Step 0: Apply product-ID remappings (draft → promoted) before loading
        if (command.productIdRemappings() != null && !command.productIdRemappings().isEmpty()) {
            saleRepository.remapItemProductIds(command.saleId(), command.productIdRemappings());
        }

        Sale sale = loadPendingSale(command.saleId());
        enforceEmployeeStoreScope(command.assignedStoreId(), sale.getStoreId());
        validateJustification(command.justification());

        // Story 13.9: claim PENDING_VALIDATION→COMPLETED atomically before any stock write or
        // event (mirrors the COMPLETED→CANCELLED claim). A concurrent cancel/validation that won
        // leaves 0 rows here: refuse before touching stock — never COMPLETED-without-stock nor
        // CANCELLED-with-decrement, and no alert for a decrement that would be rolled back.
        if (!saleRepository.updateStatusIfCurrent(
                sale.getId(), SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED)) {
            throw new DomainException(ErrorCode.SALE_NOT_PENDING,
                    "Sale " + sale.getId() + " is no longer pending validation");
        }

        // Step 1: Record initial stock entries for newly promoted draft products.
        // Uses sale.getStoreId() as the authoritative store to avoid storeId mismatch
        // between Flutter local DB and backend (JWT-assigned store).
        if (command.initialStockEntries() != null && !command.initialStockEntries().isEmpty()) {
            UUID actorId = command.actorId();
            for (var entry : command.initialStockEntries().entrySet()) {
                UUID productId = entry.getKey();
                int qty = entry.getValue();
                if (qty > 0) {
                    stockCommand.recordMovement(new StockMovementInput(
                            productId, null, sale.getStoreId(),
                            StockMovementKind.STOCK_ENTRY, qty,
                            actorId, "Stock initial à la validation du brouillon"));
                }
            }
        }

        String tenantId = TenantContext.getCurrentTenant();
        Instant now = Instant.now();

        List<UUID> forcedProducts = new ArrayList<>();

        // Decrement stock for each item — force to 0 if insufficient. Availability is read on the
        // level that is decremented (the item's variant, null for a simple product), as
        // RecordSaleService does (Story 12.2c; previously the no-variant level was read).
        for (SaleItem item : sale.getItems()) {
            int available = stockQuery.availableQuantity(item.getProductId(), item.getVariantId(), sale.getStoreId());

            int effectiveDecrement = Math.min(available, item.getQuantity());

            if (available < item.getQuantity()) {
                // Force stock to 0
                forcedProducts.add(item.getProductId());
                eventPublisher.publishEvent(new StockForcedZeroEvent(
                        item.getProductId(), sale.getStoreId(),
                        item.getQuantity(), available,
                        command.actorId(), tenantId, now));
            }

            if (effectiveDecrement > 0) {
                stockCommand.recordMovement(new StockMovementInput(
                        item.getProductId(),
                        item.getVariantId(),
                        sale.getStoreId(),
                        StockMovementKind.SALE,
                        -effectiveDecrement,
                        command.actorId(),
                        "Validation manuelle vente " + sale.getId(),
                        // Story v1s-13-10: the sale item id is the SALE movement id on both app and
                        // backend, so the device's pull does not add a second SALE movement.
                        item.getId(),
                        false
                ));
            }
        }

        eventPublisher.publishEvent(new SaleManuallyValidatedEvent(
                sale.getId(), command.actorId(), command.justification(),
                forcedProducts, tenantId, now, sale.getStoreId()));

        eventPublisher.publishEvent(new SaleCompletedEvent(
                sale.getId(), command.actorId(), tenantId, sale.getStoreId(),
                sale.getTotalAmount(), sale.getDiscountAmount(),
                serializeItems(sale.getItems(), sale.getId()), now));
    }

    @Override
    public void cancelPendingSale(CancelPendingSaleCommand command) {
        Sale sale = loadSale(command.saleId());
        String tenantId = TenantContext.getCurrentTenant();

        switch (sale.getStatus()) {
            case CANCELLED -> throw new DomainException(ErrorCode.SALE_ALREADY_CANCELLED,
                    "Sale " + sale.getId() + " is already cancelled");
            case PENDING_VALIDATION -> {
                enforceEmployeeStoreScope(command.assignedStoreId(), sale.getStoreId());
                validateJustification(command.justification());
                // Story 13.9: claim PENDING_VALIDATION→CANCELLED atomically; a concurrent
                // validation/cancellation that won leaves 0 rows here.
                if (!saleRepository.updateStatusIfCurrent(
                        sale.getId(), SaleStatus.PENDING_VALIDATION, SaleStatus.CANCELLED)) {
                    throw lostPendingRace(sale.getId(), command.pendingOnly());
                }
                eventPublisher.publishEvent(new SaleCancelledEvent(
                        sale.getId(), command.actorId(), command.justification(),
                        serializeItems(sale.getItems(), sale.getId()), tenantId, Instant.now(),
                        sale.getStoreId()));
            }
            case COMPLETED -> {
                if (command.pendingOnly()) {
                    throw completedViaPendingOnly();
                }
                enforceEmployeeStoreScope(command.assignedStoreId(), sale.getStoreId());
                requireJustification(command.justification());
                // Claim the COMPLETED→CANCELLED transition first: a concurrent cancellation that
                // read the same COMPLETED sale loses here and must not restore the stock again.
                // Story v1s-13-10: the claim also checks the version read above, so a cancel that
                // read pre-correction items cannot restore the wrong quantities.
                if (!saleRepository.updateStatusIfCurrent(
                        sale.getId(), SaleStatus.COMPLETED, sale.getVersion(), SaleStatus.CANCELLED)) {
                    throw lostCompletedRace(sale.getId());
                }
                for (SaleItem item : sale.getItems()) {
                    // v1s-13-13: deterministic id (same on the device), so the pull does not
                    // add a second SALE_CANCELLED row to the history.
                    stockCommand.recordMovement(new StockMovementInput(
                            item.getProductId(), item.getVariantId(), sale.getStoreId(),
                            StockMovementKind.SALE_CANCELLED, item.getQuantity(),
                            command.actorId(), "Annulation vente " + sale.getId(),
                            DeterministicIds.saleCancelledMovementId(item.getId()), false));
                }
                eventPublisher.publishEvent(new SaleCancelledEvent(
                        sale.getId(), command.actorId(), command.justification(),
                        serializeItems(sale.getItems(), sale.getId()), tenantId, Instant.now(),
                        sale.getStoreId()));
            }
        }
    }

    @Override
    public List<Sale> getPendingSales() {
        return saleRepository.findByStatus(SaleStatus.PENDING_VALIDATION);
    }

    @Override
    public List<Sale> getPendingSalesByStore(UUID storeId) {
        return saleRepository.findByStoreIdAndStatus(storeId, SaleStatus.PENDING_VALIDATION);
    }

    /** Story v1s-13-5 AC5 (Décision D2): the sync channel never cancels a COMPLETED sale. */
    private static DomainException completedViaPendingOnly() {
        return new DomainException(ErrorCode.FORBIDDEN,
                "Cancelling a completed sale requires an online OWNER-authenticated request");
    }

    /** Maps a lost PENDING_VALIDATION→CANCELLED claim to the error matching the winner's outcome. */
    private DomainException lostPendingRace(UUID saleId, boolean pendingOnly) {
        SaleStatus current = saleRepository.findById(saleId).map(Sale::getStatus).orElse(null);
        if (current == SaleStatus.CANCELLED) {
            return new DomainException(ErrorCode.SALE_ALREADY_CANCELLED,
                    "Sale " + saleId + " is already cancelled");
        }
        if (pendingOnly && current == SaleStatus.COMPLETED) {
            return completedViaPendingOnly();
        }
        return new DomainException(ErrorCode.SALE_NOT_PENDING,
                "Sale " + saleId + " is no longer pending validation (status=" + current + ")");
    }

    /**
     * Maps a lost versioned COMPLETED→CANCELLED claim: already cancelled → SALE_ALREADY_CANCELLED,
     * otherwise the sale was modified (corrected) since it was read → 409 OPTIMISTIC_LOCK.
     */
    private DomainException lostCompletedRace(UUID saleId) {
        SaleStatus current = saleRepository.findById(saleId).map(Sale::getStatus).orElse(null);
        if (current == SaleStatus.CANCELLED) {
            return new DomainException(ErrorCode.SALE_ALREADY_CANCELLED,
                    "Sale " + saleId + " is already cancelled");
        }
        return new DomainException(ErrorCode.OPTIMISTIC_LOCK,
                "Sale " + saleId + " was modified concurrently (status=" + current + ")");
    }

    private Sale loadPendingSale(UUID saleId) {
        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new DomainException(ErrorCode.SALE_NOT_FOUND,
                        "Sale not found: " + saleId));
        if (sale.getStatus() != SaleStatus.PENDING_VALIDATION) {
            throw new DomainException(ErrorCode.SALE_NOT_PENDING,
                    "Sale " + saleId + " is not pending validation (status=" + sale.getStatus() + ")");
        }
        return sale;
    }

    private Sale loadSale(UUID saleId) {
        return saleRepository.findById(saleId)
                .orElseThrow(() -> new DomainException(ErrorCode.SALE_NOT_FOUND,
                        "Sale not found: " + saleId));
    }

    private void validateJustification(String justification) {
        if (justification != null && !justification.isBlank() && justification.trim().length() < 10) {
            throw new DomainException(ErrorCode.JUSTIFICATION_TOO_SHORT,
                    "Justification must be at least 10 characters");
        }
    }

    /** Strict justification check — used only by COMPLETED-sale cancellation (Story v1s-13-5). */
    private void requireJustification(String justification) {
        JustificationPolicy.requireStrict(justification);
    }

    private String serializeItems(List<SaleItem> items, UUID saleId) {
        return SaleAuditSnapshot.serialize(objectMapper, items, saleId);
    }

    private void enforceEmployeeStoreScope(UUID assignedStoreId, UUID saleStoreId) {
        // OWNER flow passes null assignedStoreId and is unrestricted.
        if (assignedStoreId != null && !assignedStoreId.equals(saleStoreId)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Employee cannot operate pending sale outside assigned store");
        }
    }
}
