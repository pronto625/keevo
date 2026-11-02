package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.api.StockTrackingGuard;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.product.ProductStockTrackingChangedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * SetProductStockTrackingUseCase — turns a product's stock tracking on or off (Story 17.12). The single path of
 * every tracking change: the dedicated operation (REST / sync, through {@code inventory}) and a product edit that
 * carries {@code trackStock}.
 *
 * <p>The setting belongs to the product (all variants, all stores); it is never derived from the sector or the
 * store type. Rules: OWNER only (there is no manager role with catalogue rights); refused while the
 * {@code inventory} module is DISABLED; turning it off is refused while a transfer of the product is in transit
 * ({@link StockTrackingGuard}, implemented by inventory). The write is a conditional update: of two concurrent
 * identical calls only one reports {@code changed}, and only that one is audited (« Suivi du stock activé /
 * désactivé ») — callers gate any starting-quantity entry on it. Stock levels are not touched: disabling freezes
 * them, re-enabling resumes from them (a store without a level starts at 0).
 */
@Service
public class SetProductStockTrackingUseCase {

    static final String INVENTORY_MODULE = "inventory";

    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EntitlementService entitlements;
    private final ObjectProvider<StockTrackingGuard> guard; // optional — the catalogue starts without inventory

    public SetProductStockTrackingUseCase(ProductRepository productRepository,
                                          ApplicationEventPublisher eventPublisher,
                                          EntitlementService entitlements,
                                          ObjectProvider<StockTrackingGuard> guard) {
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
        this.entitlements = entitlements;
        this.guard = guard;
    }

    /** The product after the call, and whether this call switched the setting. */
    public record Result(Product product, boolean changed) {}

    @Transactional
    public Result execute(UUID productId, boolean tracked, UUID actorId, String actorRole) {
        Product existing = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found: " + productId));
        requireOwner(actorRole);
        String tenant = TenantContext.getCurrentTenant();
        if (entitlements.state(tenant, INVENTORY_MODULE) == ModuleState.DISABLED) {
            throw new DomainException(ErrorCode.MODULE_NOT_ENTITLED,
                    "Le module Stock et inventaire n'est pas actif");
        }
        if (!tracked && existing.isTrackStock()) {
            StockTrackingGuard g = guard.getIfAvailable();
            if (g != null) g.checkCanDisable(tenant, productId);
        }
        boolean changed = productRepository.updateTrackStock(productId, tracked, Instant.now());
        Product current = productRepository.findById(productId).orElse(existing.withTrackStock(tracked));
        if (changed) {
            eventPublisher.publishEvent(new ProductStockTrackingChangedEvent(
                    current.getId(), current.getName(), tracked, tenant, actorId, Instant.now()));
        }
        return new Result(current, changed);
    }

    /** OWNER only — the same rule for the dedicated operation, a product edit and a creation untracked. */
    static void requireOwner(String actorRole) {
        if (!"OWNER".equals(actorRole)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Seul le propriétaire peut modifier le suivi du stock d'un produit");
        }
    }
}
