package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;

import java.util.List;

/**
 * Story 14.4 — the invoice data of a completed sale for {@link SaleCompletedEvent}: its typed lines (product name,
 * applied VAT-inclusive unit price, quantity) and its payment mode name. Shared by the three publishers.
 */
final class SaleCompletedLines {

    private SaleCompletedLines() {
    }

    static List<SaleCompletedEvent.Line> of(Sale sale) {
        return sale.getItems().stream()
                .map(i -> new SaleCompletedEvent.Line(i.getProductName(), i.getAppliedUnitPrice(), i.getQuantity()))
                .toList();
    }

    static String paymentMode(Sale sale) {
        return sale.getPaymentMode() == null ? null : sale.getPaymentMode().name();
    }
}
