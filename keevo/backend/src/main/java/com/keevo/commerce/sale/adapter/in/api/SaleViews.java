package com.keevo.commerce.sale.adapter.in.api;

import com.keevo.commerce.api.SaleItemView;
import com.keevo.commerce.api.SalePaymentMode;
import com.keevo.commerce.api.SaleState;
import com.keevo.commerce.api.SaleView;
import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.model.SaleStatus;

/** Maps the sale aggregate and the API enums (by name) for the {@code commerce :: api} adapters. */
final class SaleViews {

    private SaleViews() {
    }

    static SaleView of(Sale sale) {
        return new SaleView(
                sale.getId(),
                sale.getStoreId(),
                sale.getEmployeeId(),
                sale.getClientId(),
                sale.getPaymentMode() != null ? SalePaymentMode.valueOf(sale.getPaymentMode().name()) : null,
                sale.getStatus() != null ? SaleState.valueOf(sale.getStatus().name()) : null,
                sale.getTotalAmount(),
                sale.getDiscountAmount(),
                sale.getOccurredAt(),
                sale.getCreatedAt(),
                sale.getItems() == null ? null : sale.getItems().stream().map(SaleViews::of).toList());
    }

    static SaleItemView of(SaleItem item) {
        return new SaleItemView(item.getId(), item.getProductId(), item.getVariantId(), item.getProductName(),
                item.getCatalogueUnitPrice(), item.getAppliedUnitPrice(), item.getQuantity());
    }

    static PaymentMode toPaymentMode(SalePaymentMode mode) {
        return PaymentMode.valueOf(mode.name());
    }

    static SaleStatus toSaleStatus(SaleState state) {
        return state == null ? null : SaleStatus.valueOf(state.name());
    }
}
