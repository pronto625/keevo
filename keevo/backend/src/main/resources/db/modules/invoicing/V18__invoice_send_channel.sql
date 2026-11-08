-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V18 (Story 20.8, fusion Keevo × Bref FUS-FR22): sending a document by SMS or email, durable
-- send outbox (Bref's invoice_outbox). (V16 / V17 belong to stories merged before this one.)
--
-- Additive only, no data UPDATE:
--   * invoices.last_sent_to          — widened from 20 to 255 characters: it now holds an email address too (an
--     E.164 number for WhatsApp / SMS). Increasing a VARCHAR length is a catalog-only change (no table rewrite);
--   * invoices.last_sent_channel     — channel of the last delivered sending: 'WHATSAPP', 'SMS' or 'EMAIL'; NULL =
--     never sent, or sent before 20.8 (then always WhatsApp);
--   * invoices.last_send_status / last_send_status_channel / last_send_error_code — where the last queued request
--     stands (PENDING, SENT, FAILED, UNKNOWN), read by the app through the pull; NULL = none;
--   * invoice_send_requests          — one row per request and channel: an offline SEND_INVOICE queued (keyed on
--     operation_id + channel: pushed again, never sent twice) or the log of an online sending (SENT / FAILED /
--     UNKNOWN). The poller reads the due PENDING rows through a partial index on next_attempt_at; a SENDING row
--     older than its lease (worker crash mid-send) is found through a partial index on updated_at.
-- Idempotent (re-running the type change is a no-op, IF NOT EXISTS, constraints guarded). The CHECK constraints are
-- added NOT VALID then validated (no long lock on invoices). Applied lazily inside the tenant schema
-- (ModuleProvisioner.ensureProvisioned) — never at boot.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V19__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

ALTER TABLE invoices ALTER COLUMN last_sent_to TYPE VARCHAR(255);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS last_sent_channel        VARCHAR(10);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS last_send_status         VARCHAR(10);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS last_send_status_channel VARCHAR(10);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS last_send_error_code     VARCHAR(60);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'invoices'::regclass
                   AND conname = 'invoices_last_sent_channel_check') THEN
        ALTER TABLE invoices ADD CONSTRAINT invoices_last_sent_channel_check
            CHECK ((last_sent_channel IS NULL OR last_sent_channel IN ('WHATSAPP', 'SMS', 'EMAIL'))
               AND (last_send_status_channel IS NULL OR last_send_status_channel IN ('WHATSAPP', 'SMS', 'EMAIL'))
               AND (last_send_status IS NULL OR last_send_status IN ('PENDING', 'SENT', 'FAILED', 'UNKNOWN')))
            NOT VALID;
    END IF;
END $$;
ALTER TABLE invoices VALIDATE CONSTRAINT invoices_last_sent_channel_check;

CREATE TABLE IF NOT EXISTS invoice_send_requests (
    id                 UUID         PRIMARY KEY,
    invoice_id         UUID         NOT NULL,
    operation_id       VARCHAR(100),
    channel            VARCHAR(10)  NOT NULL,
    recipient_override VARCHAR(255),
    status             VARCHAR(10)  NOT NULL,
    attempts           INT          NOT NULL DEFAULT 0,
    last_error_code    VARCHAR(60),
    next_attempt_at    TIMESTAMPTZ,
    created_by         UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT invoice_send_requests_values_check
        CHECK (channel IN ('WHATSAPP', 'SMS', 'EMAIL')
           AND status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'UNKNOWN')
           AND attempts >= 0)
);

-- one request per sync operation and channel (NULLs = online sendings, never in conflict)
CREATE UNIQUE INDEX IF NOT EXISTS uq_invoice_send_requests_operation
    ON invoice_send_requests (operation_id, channel);
-- the poller: due PENDING rows, oldest first
CREATE INDEX IF NOT EXISTS idx_invoice_send_requests_due
    ON invoice_send_requests (next_attempt_at) WHERE status = 'PENDING';
-- lease recovery: SENDING rows by claim time
CREATE INDEX IF NOT EXISTS idx_invoice_send_requests_sending
    ON invoice_send_requests (updated_at) WHERE status = 'SENDING';
