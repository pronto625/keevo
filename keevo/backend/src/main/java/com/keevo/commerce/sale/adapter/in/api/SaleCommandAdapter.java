package com.keevo.commerce.sale.adapter.in.api;

import com.keevo.commerce.api.CancelSaleInput;
import com.keevo.commerce.api.RecordSaleInput;
import com.keevo.commerce.api.SaleCommandPort;
import com.keevo.commerce.api.ValidateSaleInput;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase.CancelPendingSaleCommand;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.RecordSaleCommand;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.SaleItemCommand;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase.ValidateSaleCommand;
import org.springframework.stereotype.Component;

/**
 * Implements the published {@link SaleCommandPort} by delegating to the existing sale use cases
 * (Story 12.2c). No transaction of its own: the use cases' {@code @Transactional} applies (joined
 * when the caller already has one), so a sale and its stock movements stay atomic.
 */
@Component
class SaleCommandAdapter implements SaleCommandPort {

    private final RecordSaleUseCase recordSaleUseCase;
    private final ValidateSaleUseCase validateSaleUseCase;
    private final CancelPendingSaleUseCase cancelPendingSaleUseCase;

    SaleCommandAdapter(RecordSaleUseCase recordSaleUseCase,
                       ValidateSaleUseCase validateSaleUseCase,
                       CancelPendingSaleUseCase cancelPendingSaleUseCase) {
        this.recordSaleUseCase = recordSaleUseCase;
        this.validateSaleUseCase = validateSaleUseCase;
        this.cancelPendingSaleUseCase = cancelPendingSaleUseCase;
    }

    @Override
    public void recordSale(RecordSaleInput in) {
        var items = in.items().stream().map(l -> new SaleItemCommand(
                l.itemId(), l.productId(), l.variantId(), l.productName(),
                l.catalogueUnitPrice(), l.appliedUnitPrice(), l.quantity())).toList();
        recordSaleUseCase.recordSale(new RecordSaleCommand(
                in.saleId(), in.actorId(), in.storeId(), in.clientId(),
                SaleViews.toPaymentMode(in.paymentMode()), in.mobileMoneyRef(), in.discountAmount(),
                SaleViews.toSaleStatus(in.requestedStatus()), items, in.occurredAt(), in.allowNegativeStock()));
    }

    @Override
    public void validateSale(ValidateSaleInput in) {
        validateSaleUseCase.validateSale(new ValidateSaleCommand(
                in.saleId(), in.actorId(), in.assignedStoreId(), in.justification(),
                in.productIdRemappings(), in.initialStockEntries()));
    }

    @Override
    public void cancelSale(CancelSaleInput in) {
        cancelPendingSaleUseCase.cancelPendingSale(new CancelPendingSaleCommand(
                in.saleId(), in.actorId(), in.assignedStoreId(), in.justification(), in.pendingOnly()));
    }
}
