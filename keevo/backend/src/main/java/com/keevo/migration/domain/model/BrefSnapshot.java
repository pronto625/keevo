package com.keevo.migration.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Story 24.2 — what the import reads of one Bref owner: the owner, every shop of the owner (deleted ones included, so
 * the documents of 24.4 keep their store), every member account attached to those shops (deleted ones included, the
 * import skips and counts them). Both source adapters produce the same snapshot for the same data. 24.3–24.6 extend it
 * with their sections: 24.3 adds the products and the customers of the owner's shops; 24.4 the invoices (sales and
 * quotes, with their lines and payments) and the expenses of those shops.
 */
public record BrefSnapshot(BrefAccount owner, List<BrefShop> shops, List<BrefAccount> members,
                           List<BrefProduct> products, List<BrefCustomer> customers, List<BrefInvoice> invoices,
                           List<BrefExpense> expenses, SourceKind source) {

    public BrefSnapshot {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(source, "source");
        shops = List.copyOf(shops);
        members = List.copyOf(members);
        products = products == null ? List.of() : List.copyOf(products);
        customers = customers == null ? List.of() : List.copyOf(customers);
        invoices = invoices == null ? List.of() : List.copyOf(invoices);
        expenses = expenses == null ? List.of() : List.copyOf(expenses);
    }

    /** A snapshot without catalogue nor customers (24.2 shape). */
    public BrefSnapshot(BrefAccount owner, List<BrefShop> shops, List<BrefAccount> members, SourceKind source) {
        this(owner, shops, members, List.of(), List.of(), List.of(), List.of(), source);
    }

    /** A snapshot without documents nor expenses (24.3 shape). */
    public BrefSnapshot(BrefAccount owner, List<BrefShop> shops, List<BrefAccount> members,
                        List<BrefProduct> products, List<BrefCustomer> customers, SourceKind source) {
        this(owner, shops, members, products, customers, List.of(), List.of(), source);
    }
}
