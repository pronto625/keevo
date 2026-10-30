-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V4 (Story 14.7): credit notes (« avoirs ») on issued / paid invoices.
--
-- Additive only:
--   * credit_notes / credit_note_items / credit_note_counters — new tables. A credit note references an ISSUED or
--     PAID invoice for all or some of its lines; it has its own gapless series AV-<yyyy>-<000001> (counter per
--     calendar year, Africa/Douala, incremented in the issuing transaction). Amounts are stored positive: the
--     « avoir » meaning and the minus sign are presentation. Credit notes are never modified nor deleted.
--   * invoices.superseded_at — set only when a sale correction replaces the automatic invoice of a sale by a new
--     one (the old invoice is fully credited first). NULL for every other invoice.
--   * the V3 unique index on invoices.source_sale_id becomes partial (WHERE superseded_at IS NULL): one ACTIVE
--     invoice per sale, so a corrected sale can get its new invoice.
-- Applied lazily inside the tenant schema on the first gated call or bridge delivery after the deploy
-- (ModuleProvisioner.ensureProvisioned) — never at boot.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V5__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS superseded_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_invoices_source_sale ON invoices (source_sale_id);
DROP INDEX IF EXISTS uq_invoices_source_sale;
CREATE UNIQUE INDEX IF NOT EXISTS uq_invoices_active_source_sale ON invoices (source_sale_id)
    WHERE superseded_at IS NULL;

CREATE TABLE IF NOT EXISTS credit_notes (
    id            UUID         PRIMARY KEY,
    number        VARCHAR(20)  NOT NULL UNIQUE,
    invoice_id    UUID         NOT NULL REFERENCES invoices (id),
    reason        VARCHAR(300) NOT NULL,
    total_ht      BIGINT       NOT NULL CHECK (total_ht >= 0),
    total_vat     BIGINT       NOT NULL CHECK (total_vat >= 0),
    total_ttc     BIGINT       NOT NULL CHECK (total_ttc >= 0),
    issued_at     TIMESTAMPTZ  NOT NULL,
    created_by    UUID,
    -- idempotency key of an automatic credit note (sale cancelled / corrected); NULL for a manual one
    source_event  VARCHAR(60)  UNIQUE
);

CREATE INDEX IF NOT EXISTS idx_credit_notes_invoice ON credit_notes (invoice_id);

CREATE TABLE IF NOT EXISTS credit_note_items (
    credit_note_id   UUID          NOT NULL REFERENCES credit_notes (id),
    position         INT           NOT NULL CHECK (position >= 1),
    invoice_position INT           NOT NULL CHECK (invoice_position >= 1),
    description      VARCHAR(500)  NOT NULL,
    quantity         NUMERIC(12,3) NOT NULL CHECK (quantity > 0),
    unit_price       BIGINT        NOT NULL CHECK (unit_price >= 0),
    vat_treatment    VARCHAR(12)   NOT NULL CHECK (vat_treatment IN ('STANDARD', 'EXEMPT', 'STATE_BORNE')),
    vat_rate         NUMERIC(5,2)  NOT NULL,
    total_ht         BIGINT        NOT NULL CHECK (total_ht >= 0),
    total_vat        BIGINT        NOT NULL CHECK (total_vat >= 0),
    total_ttc        BIGINT        NOT NULL CHECK (total_ttc >= 0),
    UNIQUE (credit_note_id, position)
);

-- Gapless numbering of credit notes: one counter per calendar year (Africa/Douala), independent of invoices.
CREATE TABLE IF NOT EXISTS credit_note_counters (
    year       INT  PRIMARY KEY,
    last_value INT  NOT NULL CHECK (last_value >= 0)
);
