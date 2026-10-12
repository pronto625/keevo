package com.keevo.commerce.sale.adapter.out.export;

import com.keevo.commerce.sale.domain.model.PaymentMode;

import java.util.Map;

/** French labels of sale codes in exports (Story v1s-18-2). */
final class SaleLabels {

    private SaleLabels() {
    }

    static final Map<String, String> STATUS = Map.of(
            "COMPLETED", "Validée",
            "CANCELLED", "Annulée",
            "PENDING_VALIDATION", "En attente de validation");

    static final Map<String, String> PAYMENT_MODE = Map.of(
            PaymentMode.CASH.name(), "Espèces",
            PaymentMode.MOBILE_MONEY.name(), "Mobile Money");

    /** Sale date: when it happened on the device, else when the server recorded it. */
    static final String SALE_DATE = "COALESCE(s.occurred_at, s.created_at)";
}
