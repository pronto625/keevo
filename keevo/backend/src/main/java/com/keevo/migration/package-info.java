// Story 24.2 — the « migration » platform module (SUPER_ADMIN, temporary until epic 25): imports a Bref owner into Keevo
// from a read-only snapshot (api-bref JSON export, contract keevo/contracts/bref-export.json, or a read-only JDBC
// connection to a restored Bref database), step by step and idempotently, tracing every written row in
// public.migration_records and every run in public.migration_batches. 24.2 imports the identity, the tenant, the shops
// and the team; 24.3 the categories, products and clients; 24.4–24.6 append steps (documents, monetisation, annexes),
// 24.8 reconciles, 24.9 runs the one-shot cutover (ADR-10: every owner of a restored Bref database, activation,
// rollback).
// Writes go through the owning modules' import ports: identity (accounts, tenant, members, sale flow, sector
// templates), store (shops, logos), shared.module (activation rules), catalog (categories, products — 24.3), crm
// (clients — 24.3).
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"catalog :: api", "crm :: api", "identity :: api", "shared", "store :: api"}
)
package com.keevo.migration;
