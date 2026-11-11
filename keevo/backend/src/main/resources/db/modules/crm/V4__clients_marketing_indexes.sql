-- ─────────────────────────────────────────────────────────────────────────────
-- Module crm — V4 (Story 21.6, fusion Keevo × Bref, FUS-FR28)
--
-- Indexes of the WhatsApp campaigns (whatsapp-cloud, through crm.api):
--   idx_clients_phone_normalized      a client's STOP / START received on the company's WhatsApp number updates every
--                                     client of the company with that number (any store) — one index probe.
--   idx_clients_marketing_audience    the campaign audience: clients with the team's consent, no STOP, not archived,
--                                     de-duplicated by phone — a scan of the eligible clients only.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V5__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_clients_phone_normalized ON clients (phone_normalized);
CREATE INDEX IF NOT EXISTS idx_clients_marketing_audience ON clients (phone_normalized, updated_at DESC)
    WHERE marketing_consent_at IS NOT NULL AND marketing_opt_out_at IS NULL AND archived = false;
