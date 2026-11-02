package com.keevo.inventory.stock.domain.service;

import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * StockTrackingPolicy — whether a product's stock is tracked (Story 17.12).
 *
 * <p>A product is tracked when its catalogue setting {@code products.track_stock} is on <b>and</b> the
 * {@code inventory} module is not DISABLED for the tenant (subscribed, trial, or read-only grace — a sale during
 * the grace keeps decrementing, so levels never drift). Without the module (DISABLED, or no subscription) every
 * product behaves as untracked: sales never check availability nor write SALE movements. Never derived from the sector,
 * the store type or the category.
 *
 * <p>An unknown product counts as tracked, so the existing PRODUCT_NOT_FOUND paths are unchanged.
 */
@Component
public class StockTrackingPolicy {

    /** Module whose activation is required for any stock tracking. */
    public static final String INVENTORY_MODULE = "inventory";

    private final ProductQueryPort productQuery;
    private final EntitlementService entitlements;

    public StockTrackingPolicy(ProductQueryPort productQuery, EntitlementService entitlements) {
        this.productQuery = productQuery;
        this.entitlements = entitlements;
    }

    /** True when the product's stock must be ignored (setting off, or inventory module DISABLED). */
    public boolean isUntracked(String tenantSchema, UUID productId) {
        if (!inventoryEnabled(tenantSchema)) {
            return true;
        }
        return productQuery.findById(tenantSchema, productId)
                .map(p -> !p.trackStock())
                .orElse(false);
    }

    /** Whether the inventory module is usable for the tenant (ENABLED — trial included — or READ_ONLY grace). */
    public boolean inventoryEnabled(String tenantSchema) {
        return entitlements.state(tenantSchema, INVENTORY_MODULE) != ModuleState.DISABLED;
    }

    /**
     * Refuses a stock movement on a product whose tracking is off (entry, adjustment, transfer, inventory).
     *
     * @throws DomainException {@code STOCK_NOT_TRACKED}
     */
    public static void requireTracked(ProductView product) {
        if (!product.trackStock()) {
            throw notTracked();
        }
    }

    /** The stable refusal of a stock operation on an untracked product. */
    public static DomainException notTracked() {
        return new DomainException(ErrorCode.STOCK_NOT_TRACKED,
                "Le suivi du stock est désactivé pour ce produit");
    }
}
