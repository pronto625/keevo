package com.keevo.inventory.stock.application.usecase;

import com.keevo.catalog.api.ProductCommandPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.api.StockTrackingUpdate;
import com.keevo.inventory.api.StockTrackingChange;
import com.keevo.inventory.stock.domain.entity.MovementType;
import com.keevo.inventory.stock.domain.entity.StockMovement;
import com.keevo.inventory.stock.domain.service.StockOperationService;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreQueryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ChangeStockTrackingUseCase — enables or disables a product's stock tracking (Story 17.12).
 *
 * <ul>
 *   <li>Enabling: optional starting quantity for ONE store (the owner's active store) → one STOCK_ENTRY, same
 *       mechanics as the 17.11 initial stock; every other store starts at 0 (no row). Empty / 0 → no movement.
 *       Re-enabling resumes from the frozen quantities. A quantity sent while the product is already tracked is
 *       ignored (no second entry on a replay from another device).</li>
 *   <li>Disabling: the levels are kept, frozen (no movement while untracked); the history stays readable.</li>
 * </ul>
 * OWNER only; the setting change is audited by the catalogue (« Suivi du stock activé / désactivé »), the entry by
 * the usual stock audit. One transaction: the setting and the entry commit or roll back together.
 */
@Service
public class ChangeStockTrackingUseCase {

    /** Note of the starting-quantity movement (shown in the stock history). */
    public static final String INITIAL_STOCK_NOTE = "Stock de départ (activation du suivi)";

    private final ProductCommandPort productCommand;
    private final StoreQueryPort storeQuery;
    private final StockOperationService stockOperationService;

    public ChangeStockTrackingUseCase(ProductCommandPort productCommand,
                                      StoreQueryPort storeQuery,
                                      StockOperationService stockOperationService) {
        this.productCommand = productCommand;
        this.storeQuery = storeQuery;
        this.stockOperationService = stockOperationService;
    }

    /**
     * Outcome: the product after the change, and the starting-quantity entry when one was recorded — {@code null}
     * when none was asked, or when it was ignored because the product was already tracked.
     */
    public record Result(ProductView product, StockMovement initialEntry) {}

    @Transactional
    public Result execute(StockTrackingChange change) {
        String tenant = TenantContext.getCurrentTenant();
        if (!"OWNER".equals(change.actorRole())) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Seul le propriétaire peut modifier le suivi du stock d'un produit");
        }
        Integer quantity = change.initialQuantity();
        if (quantity != null && quantity < 0) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "La quantité de départ doit être un nombre entier positif");
        }
        boolean withEntry = quantity != null && quantity > 0;
        if (withEntry && !change.tracked()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Une quantité de départ n'est possible qu'à l'activation du suivi du stock");
        }
        if (withEntry && change.storeId() == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Choisissez la boutique de la quantité de départ");
        }
        if (withEntry) {
            var store = storeQuery.findById(tenant, change.storeId())
                    .orElseThrow(() -> new DomainException(ErrorCode.STORE_NOT_FOUND, "Boutique introuvable"));
            if (!store.active()) {
                throw new DomainException(ErrorCode.STORE_NOT_ACTIVE, "Cette boutique est désactivée");
            }
        }

        // Conditional update in the catalogue: only the call that switched the setting records the starting entry
        // (two concurrent activations never double the stock, a replay on a tracked product adds nothing).
        StockTrackingUpdate update = productCommand.setStockTracking(tenant, change.productId(), change.tracked(),
                change.actorId(), change.actorRole());
        ProductView after = update.product();

        StockMovement entry = null;
        if (withEntry && update.changed()) {
            entry = stockOperationService.recordOperation(
                    change.productId(), null, change.storeId(),
                    MovementType.STOCK_ENTRY, quantity,
                    change.actorId(), INITIAL_STOCK_NOTE, change.movementId(), false);
        }
        return new Result(after, entry);
    }
}
