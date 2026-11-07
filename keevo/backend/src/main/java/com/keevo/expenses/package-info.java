// Story 20.10 — the « Dépenses » module (Bref expenses, FUS-FR25): premium, own table (db/modules/expenses), JDBC
// adapters. Depends on the shared core and on "store :: api" only (the stores of the tenant, read through
// StoreQueryPort to place and scope an expense — ADR-08); never on another business module.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared", "store :: api"}
)
package com.keevo.expenses;
