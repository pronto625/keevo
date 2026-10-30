package com.keevo.commerce.sale.application.service;

import com.keevo.crm.api.ClientLookupPort;
import com.keevo.inventory.api.StockCommandPort;
import com.keevo.inventory.api.StockMovementInput;
import com.keevo.inventory.api.StockMovementKind;
import com.keevo.inventory.api.StockQueryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.event.catalog.product.SalePriceOverriddenEvent;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
import com.keevo.commerce.sale.domain.model.SaleFactory;
import com.keevo.shared.event.catalog.sale.SalePendingValidationEvent;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.CapabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * RecordSaleService — application service implementing RecordSaleUseCase.
 *
 * <p>GoF Command: receives RecordSaleCommand, delegates to SaleFactory.
 * <p>GoF Observer: publishes SaleCompletedEvent for AuditEventListener.
 * <p>Hexagonal: imports ONLY from domain/ and shared/ — zero Spring Data/JPA.
 *
 * <p>Story 14.3: the sale client is checked through the optional {@code crm} capability {@link ClientLookupPort}
 * (resolved per call, never injected) — REST and sync alike. crm on + unknown client → {@code CLIENT_NOT_FOUND}
 * (404 / sync REJECTED) before anything is written (the {@code sales.client_id} FK used to answer 500); crm off → the
 * sale is recorded <b>without</b> its client (money and stock must never be refused because an optional module is
 * off), with a WARN log.
 */
@Service
@Transactional
public class RecordSaleService implements RecordSaleUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordSaleService.class);

    private final SaleRepository saleRepository;
    private final StockQueryPort stockQuery;
    private final StockCommandPort stockCommand;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final CapabilityRegistry capabilities;

    public RecordSaleService(SaleRepository saleRepository,
                             StockQueryPort stockQuery,
                             StockCommandPort stockCommand,
                             ApplicationEventPublisher eventPublisher,
                             ObjectMapper objectMapper,
                             CapabilityRegistry capabilities) {
        this.capabilities = capabilities;
        this.saleRepository = saleRepository;
        this.stockQuery = stockQuery;
        this.stockCommand = stockCommand;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void recordSale(RecordSaleCommand recorded) {
        String tenantSchema = TenantContext.getCurrentTenant();
        // Idempotency guard — duplicate saleId is a no-op
        if (saleRepository.existsById(recorded.saleId())) {
            return;
        }
        RecordSaleCommand command = withCheckedClient(recorded, tenantSchema);

        SaleStatus effectiveStatus = command.requestedStatus() != null
                ? command.requestedStatus() : SaleStatus.COMPLETED;
        boolean isPending = effectiveStatus == SaleStatus.PENDING_VALIDATION;

        if (!isPending && !command.allowNegativeStock()) {
            // Stock availability check for each item (only for COMPLETED sales)
            for (var item : command.items()) {
                int available = getAvailableStock(tenantSchema, item.productId(), item.variantId(), command.storeId());
                if (available < item.quantity()) {
                    throw new DomainException(ErrorCode.INSUFFICIENT_STOCK,
                            "Stock insuffisant pour " + item.productName()
                                    + ": disponible=" + available + " demandé=" + item.quantity());
                }
            }
        }

        // Create Sale aggregate via factory
        Sale sale = SaleFactory.from(command, effectiveStatus);

        // Persist sale + items (single transaction)
        saleRepository.save(sale);

        if (!isPending) {
            // Decrement stock for each item (only for COMPLETED sales)
            // v1s-13-8: the SALE movement id is the (client-generated) sale item id on both
            // sides, so the device's pull upserts its own movement row instead of adding a
            // second one. itemId null → server-generated.
            for (var item : command.items()) {
                stockCommand.recordMovement(tenantSchema, new StockMovementInput(
                        item.productId(),
                        item.variantId(),
                        command.storeId(),
                        StockMovementKind.SALE,
                        -item.quantity(),
                        command.actorId(),
                        "Vente " + command.saleId(),
                        item.itemId(),
                        command.allowNegativeStock()
                ));
            }

            // Story 4.2 — Publish SalePriceOverriddenEvent for each overridden item
            for (var item : command.items()) {
                if (item.catalogueUnitPrice() != item.appliedUnitPrice()) {
                    eventPublisher.publishEvent(new SalePriceOverriddenEvent(
                            item.productId(),
                            sale.getId(),
                            item.productName(),
                            item.catalogueUnitPrice(),
                            item.appliedUnitPrice(),
                            command.actorId(),
                            tenantSchema,
                            Instant.now()
                    ));
                }
            }

            // Publish SaleCompletedEvent
            eventPublisher.publishEvent(new SaleCompletedEvent(
                    sale.getId(),
                    command.actorId(),
                    tenantSchema,
                    command.storeId(),
                    sale.getTotalAmount(),
                    sale.getDiscountAmount(),
                    serializeItems(command),
                    sale.getOccurredAt(),
                    sale.getClientId(),
                    SaleCompletedLines.paymentMode(sale),
                    SaleCompletedLines.of(sale)
            ));
        } else {
            // PENDING_VALIDATION: publish SalePendingValidationEvent, no stock operations
            var draftProductIds = command.items().stream()
                    .map(RecordSaleUseCase.SaleItemCommand::productId)
                    .toList();
            eventPublisher.publishEvent(new SalePendingValidationEvent(
                    sale.getId(),
                    command.storeId(),
                    command.actorId(),
                    draftProductIds,
                    sale.getTotalAmount(),
                    tenantSchema,
                    sale.getOccurredAt()
            ));
        }
    }

    /**
     * Story 14.3 — the sale client through the optional crm capability: unknown with crm on →
     * {@code CLIENT_NOT_FOUND}; crm off → the sale goes on without its client (WARN, no id in the log).
     */
    private RecordSaleCommand withCheckedClient(RecordSaleCommand command, String tenantSchema) {
        if (command.clientId() == null) {
            return command;
        }
        Optional<ClientLookupPort> crm = capabilities.find(ClientLookupPort.class, tenantSchema);
        if (crm.isPresent()) {
            if (!crm.get().exists(tenantSchema, command.clientId())) {
                throw new DomainException(ErrorCode.CLIENT_NOT_FOUND, "Unknown sale client");
            }
            return command;
        }
        log.warn("[sale] crm module off for tenant {}: sale recorded without its client", tenantSchema);
        return command.withoutClient();
    }

    private int getAvailableStock(String tenantSchema, UUID productId, UUID variantId, UUID storeId) {
        return stockQuery.availableQuantity(tenantSchema, productId, variantId, storeId);
    }

    private String serializeItems(RecordSaleCommand command) {
        return SaleAuditSnapshot.serialize(objectMapper, command.items(), command.saleId());
    }
}
