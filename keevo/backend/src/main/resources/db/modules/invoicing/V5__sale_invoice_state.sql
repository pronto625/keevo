-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V5 (Story 14.7): per-sale state of the POS → Invoicing bridge.
--
-- The sale events (sale.completed / sale.cancelled / sale.corrected) are delivered asynchronously and can arrive out
-- of order (an offline sync batch pushes CREATE_SALE then CANCEL_SALE; listeners run on pool threads). One row per
-- sale seen by the bridge, row-locked by every delivery of the sale (deliveries of one sale are serialised):
--   * cancelled_at — the sale was cancelled: a late sale.completed / sale.corrected issues no invoice;
--   * last_correction_at — occurredAt of the last correction applied: an older correction retried later is skipped.
-- Additive only (new table). Applied lazily inside the tenant schema (ModuleProvisioner.ensureProvisioned).
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V6__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS sale_invoice_states (
    sale_id            UUID        PRIMARY KEY,
    cancelled_at       TIMESTAMPTZ,
    last_correction_at TIMESTAMPTZ
);
