package com.keevo.migration.application;

/**
 * Story 24.2 — one step of the import pipeline (analysis §10.2). Steps run in their {@code @Order}, each in its own
 * unit of work, and are idempotent: a step finds in the {@link ImportRun} what a previous run already wrote and only
 * completes what is missing. 24.2 ships identity (10), profile (20), stores (30), logos (40), members (50); 24.3–24.6
 * append categories, products, clients (60–80), documents, expenses (90–100), monetisation (110), annexes (120).
 */
public interface ImportStep {

    void run(ImportRun run);

    /** French name of the step, for the failure label of a batch (e.g. « boutiques »). */
    String label();

    /**
     * The record target tables this step reads from the {@link ImportRun} (only those are loaded: a run never holds
     * the owner's whole record history in memory).
     */
    java.util.Set<String> targetTables();

    /**
     * Story 24.5 — the blocking cases of this step, found before any write of the run (after the snapshot validation;
     * the orchestrator blocks the batch when any step reports one). A step may load the section it needs here and keep
     * it in the run ({@link ImportRun#attach}). Default: none.
     */
    default java.util.List<com.keevo.migration.domain.model.BlockedCase> check(ImportRun run) {
        return java.util.List.of();
    }
}
