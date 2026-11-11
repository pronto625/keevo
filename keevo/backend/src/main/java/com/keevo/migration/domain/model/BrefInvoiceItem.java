package com.keevo.migration.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 24.4 — a Bref {@code invoice_item} row, unchanged ({@code invoice-item.entity.ts}): no product id (the line is
 * tied to the catalogue by its {@code (shop, productKey(name, reference))}), quantity {@code numeric(12,3)}, amounts
 * {@code decimal(14,2)}.
 */
public record BrefInvoiceItem(UUID id, UUID invoiceId, String reference, String name, String description,
                              BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) {

    public BrefInvoiceItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(invoiceId, "invoiceId");
        quantity = quantity == null ? BigDecimal.ZERO : quantity;
        unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        total = total == null ? BigDecimal.ZERO : total;
    }

    public String canonical() {
        return Canonical.of("id", id, "invoiceId", invoiceId, "reference", reference, "name", name,
                "description", description, "quantity", Canonical.decimal(quantity),
                "unitPrice", Canonical.decimal(unitPrice), "total", Canonical.decimal(total));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BrefInvoiceItem other && canonical().equals(other.canonical());
    }

    @Override
    public int hashCode() {
        return canonical().hashCode();
    }

    @Override
    public String toString() {
        return "BrefInvoiceItem[" + id + "]";
    }
}
