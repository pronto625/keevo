package com.keevo.inventory.transfer.adapter.in.api;

import com.keevo.catalog.api.StockTrackingGuard;
import com.keevo.inventory.transfer.domain.port.out.StockTransferRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Story 17.12 — implements the catalogue's {@link StockTrackingGuard}: turning stock tracking off is refused while
 * a transfer of the product is in transit (its reception would be refused afterwards, leaving it stuck).
 */
@Component
class InTransitStockTrackingGuard implements StockTrackingGuard {

    private final StockTransferRepository transferRepository;

    InTransitStockTrackingGuard(StockTransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    @Override
    public void checkCanDisable(String tenantSchema, UUID productId) {
        TenantContext.requireCurrent(tenantSchema);
        if (transferRepository.existsInTransitForProduct(productId)) {
            throw new DomainException(ErrorCode.TRANSFER_IN_TRANSIT,
                    "Un transfert de ce produit est en cours : réceptionnez-le avant de désactiver le suivi du stock");
        }
    }
}
