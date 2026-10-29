package com.keevo.inventory.transfer.adapter.in.api;

import com.keevo.inventory.api.TransferCommandPort;
import com.keevo.inventory.api.TransferInput;
import com.keevo.inventory.transfer.domain.port.in.TransferStockCommand;
import com.keevo.inventory.transfer.domain.port.in.TransferStockUseCase;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implements the published {@link TransferCommandPort} by delegating to the transfer use case
 * (Story 12.2b). No transaction of its own.
 */
@Component
class TransferCommandAdapter implements TransferCommandPort {

    private final TransferStockUseCase transferStockUseCase;

    TransferCommandAdapter(TransferStockUseCase transferStockUseCase) {
        this.transferStockUseCase = transferStockUseCase;
    }

    @Override
    public UUID transfer(String tenantSchema, TransferInput in) {
        TenantContext.requireCurrent(tenantSchema);
        return transferStockUseCase.execute(new TransferStockCommand(
                in.sourceStoreId(), in.destinationStoreId(), in.productId(), in.variantId(),
                in.quantity(), in.actorId(), in.notes(), in.clientId())).getId();
    }
}
