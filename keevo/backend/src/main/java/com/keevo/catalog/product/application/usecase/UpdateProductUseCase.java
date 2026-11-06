package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.service.ProductKeys;
import com.keevo.shared.event.catalog.product.ProductActivatedEvent;
import com.keevo.shared.event.catalog.product.ProductPriceChangedEvent;
import com.keevo.shared.event.catalog.product.ProductUpdatedEvent;
import com.keevo.shared.event.catalog.product.DraftProductValidatedEvent;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import com.keevo.subscription.api.ProductCountPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * UpdateProductUseCase — Business logic for updating products.
 *
 * <p>Story 2.4 (AC6): if the product is currently in DRAFT status and the caller
 * is an OWNER, the status is promoted to ACTIVE. Only OWNERs may promote a draft.
 * The corresponding pending draft record is acknowledged so it disappears from the badge counter
 * (12.6a: by messaging, on {@link DraftProductValidatedEvent}).
 */
@Service
public class UpdateProductUseCase {

    private final ProductRepository           productRepository;
    private final ApplicationEventPublisher   eventPublisher;
    private final ObjectMapper                objectMapper;
    private final ProductCountPort            productCountPort;
    private final TenantPlanQuery             tenantPlanQuery;
    private final PlanLimitCheck              planLimit;
    private final TransactionLockPort         transactionLock;
    private final SetProductStockTrackingUseCase stockTracking; // Story 17.12

    public UpdateProductUseCase(ProductRepository productRepository,
                                ApplicationEventPublisher eventPublisher,
                                ObjectMapper objectMapper,
                                ProductCountPort productCountPort,
                                TenantPlanQuery tenantPlanQuery,
                                PlanLimitCheck planLimit,
                                TransactionLockPort transactionLock,
                                SetProductStockTrackingUseCase stockTracking) {
        this.stockTracking     = stockTracking;
        this.productRepository = productRepository;
        this.eventPublisher    = eventPublisher;
        this.objectMapper      = objectMapper;
        this.productCountPort  = productCountPort;
        this.tenantPlanQuery   = tenantPlanQuery;
        this.planLimit         = planLimit;
        this.transactionLock   = transactionLock;
    }

    /**
     * DTO for update product request.
     *
     * <p>{@code actorRole}: "OWNER" or "EMPLOYEE". Required for DRAFT→ACTIVE guard (Story 2.4).
     * Use the existing 10-arg constructor from controllers; the new field defaults to "OWNER"
     * via the backward-compatible constructor.
     */
    public record UpdateProductDto(
        UUID    id,
        String  name,
        String  description,
        String  sku,
        UUID    categoryId,
        Integer price,
        Integer buyPrice,
        Integer transportCost,
        Integer stockQuantity,
        UUID    actorId,
        String  actorRole,    // "OWNER" or "EMPLOYEE" — Story 2.4 draft promotion guard
        Boolean trackStock,   // Story 17.12 — null keeps the current setting; a change is OWNER-only + audited
        String  barcode       // Story 20.1 — null keeps it; blank clears it; > 100 chars → 422
    ) {
        /** Without the barcode (unchanged) — Story 17.12 shape. */
        public UpdateProductDto(UUID id, String name, String description, String sku, UUID categoryId,
                                Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                                UUID actorId, String actorRole, Boolean trackStock) {
            this(id, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity,
                    actorId, actorRole, trackStock, null);
        }

        // v1s-12-10: the 10-arg constructor defaulting actorRole to "OWNER" was removed
        // (fail open — any caller skipped the draft guard). Callers pass the real role.

        /** Story 17.12 — the edit carries nothing but the stock-tracking setting. */
        public boolean onlyTrackStock() {
            return name == null && description == null && sku == null && categoryId == null && price == null
                    && buyPrice == null && transportCost == null && stockQuantity == null && barcode == null;
        }

        /** Without the stock-tracking setting (unchanged). */
        public UpdateProductDto(UUID id, String name, String description, String sku, UUID categoryId,
                                Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                                UUID actorId, String actorRole) {
            this(id, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity,
                    actorId, actorRole, null);
        }
    }

    @Transactional
    public Product execute(UpdateProductDto dto) {
        // Find existing product
        var existing = productRepository.findById(dto.id())
            .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_NOT_FOUND,
                    "Product not found: " + dto.id()));
        ProductKeys.cleanBarcode(dto.barcode()); // Story 20.1 — 422 before anything is written

        // Story 17.12: a tracking change goes through the single tracking path (owner, inventory gate, conditional
        // update, one « Suivi du stock … » audit). Absent or same value = unchanged (V1 clients never send it).
        if (dto.trackStock() != null && dto.trackStock() != existing.isTrackStock()) {
            stockTracking.execute(dto.id(), dto.trackStock(), dto.actorId(), dto.actorRole());
            if (dto.onlyTrackStock()) {
                // tracking was the only change: no « Produit modifié » entry, no promotion, nothing else written
                return productRepository.findById(dto.id()).orElseThrow();
            }
            existing = productRepository.findById(dto.id()).orElseThrow();
        } else if (dto.onlyTrackStock() && dto.trackStock() != null) {
            return existing; // same value, nothing else: no-op
        }

        // PATCH: an absent name keeps the current one; a blank one is invalid.
        // (Device test: a price-only PATCH answered « Produit introuvable ».)
        String resolvedName = dto.name() == null ? existing.getName() : dto.name().trim();
        if (resolvedName.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Le nom du produit est requis");
        }

        // Story 2.4 (AC6): DRAFT → ACTIVE promotion requires OWNER role
        boolean isPromotion = existing.getStatus() == ProductStatus.DRAFT;
        if (isPromotion && !"OWNER".equals(dto.actorRole())) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Seul le propriétaire peut valider un produit en brouillon");
        }
        // 17.10: a promoted draft becomes an active product — same limit check as a creation (same per-tenant
        // lock, fail closed). An archived draft stays out of the count once promoted: no check then.
        if (isPromotion && !Boolean.TRUE.equals(existing.getArchived())) {
            checkProductLimit();
        }

        // Story 20.1: absent barcode = unchanged, blank = cleared (validated before anything is written)
        String resolvedBarcode = dto.barcode() == null ? existing.getBarcode() : ProductKeys.cleanBarcode(dto.barcode());

        // Resolve SKU: keep existing if not provided
        String resolvedSku = (dto.sku() != null) ? dto.sku() : existing.getSku();

        // Validate SKU uniqueness (only if SKU actually changed)
        if (!existing.getSku().equals(resolvedSku)) {
            if (productRepository.findBySku(resolvedSku).isPresent()) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR,
                        "La référence « " + resolvedSku + " » est déjà utilisée");
            }
        }

        // Resolve status: DRAFT → ACTIVE when owner updates; otherwise preserve
        ProductStatus resolvedStatus = isPromotion ? ProductStatus.ACTIVE : existing.getStatus();

        // Create updated product (null-safe: preserve existing values if not provided)
        var updated = new Product(
            existing.getId(),
            resolvedName,
            dto.description(),
            resolvedSku,
            dto.categoryId() != null ? dto.categoryId() : existing.getCategoryId(),
            dto.price() != null ? dto.price() : existing.getPriceValue(),
            dto.buyPrice() != null ? dto.buyPrice() : existing.getBuyPriceValue(),
            dto.transportCost() != null ? dto.transportCost() : existing.getTransportCostValue(),
            // 17.12: the legacy stock column is never written for an untracked product
            !existing.isTrackStock() ? null
                    : dto.stockQuantity() != null ? dto.stockQuantity() : existing.getStockQuantity(),
            existing.getArchived(),
            resolvedStatus,
            existing.getMinimumThreshold(),
            existing.isTrackStock(),
            resolvedBarcode,
            // Story 20.1 — the key is recomputed only when the name or the barcode changes
            resolvedName.equals(existing.getName()) && java.util.Objects.equals(resolvedBarcode, existing.getBarcode())
                    ? existing.getProductKey() : ProductKeys.key(resolvedName, resolvedBarcode),
            dto.price() != null || existing.isPriceManual(),   // Story 20.1 — a price typed by a person
            existing.getMergedIntoId(),
            existing.getCreatedAt(),
            Instant.now() // updatedAt refreshed
        );

        // Save and publish event
        var saved = productRepository.save(updated);
        String tenantId = TenantContext.getCurrentTenant();

        // Acknowledge draft notification so the badge counter decrements (AC6)
        if (isPromotion) {
            eventPublisher.publishEvent(new DraftProductValidatedEvent(
                    saved.getId(), dto.actorId(), tenantId, Instant.now()));

            // Story 4.3 — publish ProductActivatedEvent for cascade auto-validation
            eventPublisher.publishEvent(new ProductActivatedEvent(
                    saved.getId(), dto.actorId(), tenantId, Instant.now()));
        }

        // Publish domain event for audit trail with valueBefore/valueAfter
        String valueBefore = toJson(existing);
        String valueAfter = toJson(saved);
        
        eventPublisher.publishEvent(new ProductUpdatedEvent(
            saved.getId(),
            saved.getName(),
            valueBefore,
            valueAfter,
            tenantId,
            dto.actorId(),
            Instant.now()
        ));

        // Story 17.7: a real catalogue price change (any role — the owner notification keeps employees only)
        if (dto.price() != null && dto.price() != existing.getPriceValue()) {
            eventPublisher.publishEvent(new ProductPriceChangedEvent(
                saved.getId(),
                saved.getName(),
                existing.getPriceValue(),
                saved.getPriceValue(),
                dto.actorId(),
                dto.actorRole(),
                tenantId,
                Instant.now()
            ));
        }

        return saved;
    }

    /**
     * Serialize Product to JSON for audit trail.
     * Returns "{}" on error — audit is best-effort.
     */
    private String toJson(Product product) {
        try {
            // Use HashMap to allow null values (Map.of disallows null)
            var map = new java.util.HashMap<String, Object>();
            map.put("id", product.getId());
            map.put("name", product.getName());
            map.put("description", product.getDescription());
            map.put("sku", product.getSku());
            map.put("categoryId", product.getCategoryId());
            map.put("barcode", product.getBarcode());
            map.put("archived", product.getArchived());
            map.put("status", product.getStatus());
            map.put("createdAt", product.getCreatedAt());
            map.put("updatedAt", product.getUpdatedAt());
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private void checkProductLimit() {
        String tenant = TenantContext.getCurrentTenant();
        transactionLock.lock("product-limit:" + tenant);
        var plan = tenantPlanQuery.findCurrentPlan(tenant)
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "Aucun abonnement actif"));
        planLimit.checkProductLimit(plan, () -> productCountPort.countActiveProducts(tenant));
    }
}
