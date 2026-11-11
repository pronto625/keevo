-- ─────────────────────────────────────────────────────────────────────────────
-- Module whatsapp-cloud — V3 (Story 21.11, fusion Keevo × Bref, FUS-FR28)
--
-- Purchase-behaviour targeting of the company's WhatsApp campaigns (Bref targetingCriteria): optional criteria saved
-- with the campaign and applied when its audience is counted and frozen at start. NULL = criterion not set (every
-- existing campaign: unchanged behaviour).
--   inactive_for_days       no purchase for N days (never-buyers included)
--   purchased_within_days   a purchase in the last N days
--   total_spent_min / max   net total of the purchases (FCFA), inclusive bounds
--   total_invoices_min / max number of purchases (sales + invoices), inclusive bounds
-- Purchases are read from the revenue source (sales, invoices, credit notes) through crm: no column here duplicates
-- them, so no backfill and no index (the campaign table is read by id / keyset only).
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V4__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

ALTER TABLE whatsapp_campaigns ADD COLUMN IF NOT EXISTS inactive_for_days     INTEGER;
ALTER TABLE whatsapp_campaigns ADD COLUMN IF NOT EXISTS purchased_within_days INTEGER;
ALTER TABLE whatsapp_campaigns ADD COLUMN IF NOT EXISTS total_spent_min       BIGINT;
ALTER TABLE whatsapp_campaigns ADD COLUMN IF NOT EXISTS total_spent_max       BIGINT;
ALTER TABLE whatsapp_campaigns ADD COLUMN IF NOT EXISTS total_invoices_min    INTEGER;
ALTER TABLE whatsapp_campaigns ADD COLUMN IF NOT EXISTS total_invoices_max    INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'whatsapp_campaigns'::regclass
                   AND conname = 'whatsapp_campaigns_purchase_criteria_check') THEN
        ALTER TABLE whatsapp_campaigns ADD CONSTRAINT whatsapp_campaigns_purchase_criteria_check CHECK (
            (inactive_for_days IS NULL OR inactive_for_days BETWEEN 1 AND 3650)
            AND (purchased_within_days IS NULL OR purchased_within_days BETWEEN 1 AND 3650)
            AND (total_spent_min IS NULL OR total_spent_min BETWEEN 0 AND 1000000000000)
            AND (total_spent_max IS NULL OR total_spent_max BETWEEN 0 AND 1000000000000)
            AND (total_invoices_min IS NULL OR total_invoices_min BETWEEN 0 AND 10000000)
            AND (total_invoices_max IS NULL OR total_invoices_max BETWEEN 0 AND 10000000));
    END IF;
END
$$;
