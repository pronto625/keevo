-- ─────────────────────────────────────────────────────────────────────────────
-- Module whatsapp-cloud — V2 (Story 21.6, fusion Keevo × Bref, FUS-FR28)
--
-- The company's WhatsApp campaigns to its consenting clients (Bref customer_campaign), in the tenant schema.
--
-- whatsapp_campaigns            one campaign: an approved template of the company, its variables (client name, store
--                               name or fixed text), the targeted stores (empty = every store), the header media of a
--                               media template, its status DRAFT → SCHEDULED → SENDING → SENT (or CANCELLED) and its
--                               counters (sent, delivered, read, failed, skipped, uncertain). The template is frozen
--                               (template_*) when the send starts. At most one SENDING at a time (partial unique
--                               index): one number = one throughput budget.
-- whatsapp_campaign_recipients  the audience frozen when the send starts, one row per phone: PENDING, claimed SENDING
--                               just before the call to Meta (at most once), then SENT / FAILED / SKIPPED / UNCERTAIN.
-- whatsapp_messages.campaign_id the campaign of a sent message: Meta's statuses update the campaign counters.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V3__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

ALTER TABLE whatsapp_messages ADD COLUMN IF NOT EXISTS campaign_id UUID;

CREATE TABLE IF NOT EXISTS whatsapp_campaigns (
    id                UUID          PRIMARY KEY,
    name              VARCHAR(100)  NOT NULL,
    description       VARCHAR(500),
    status            VARCHAR(10)   NOT NULL CONSTRAINT whatsapp_campaigns_status_check
                                        CHECK (status IN ('DRAFT', 'SCHEDULED', 'SENDING', 'SENT', 'CANCELLED')),
    store_ids         UUID[]        NOT NULL DEFAULT '{}',
    template_id       UUID,
    variables         JSONB         NOT NULL DEFAULT '[]'::jsonb,
    header_media_id   VARCHAR(128),
    header_media_link VARCHAR(2048),
    template_name     VARCHAR(512),
    template_language VARCHAR(10),
    template_body     VARCHAR(1024),
    template_header   VARCHAR(10),
    scheduled_at      TIMESTAMPTZ,
    started_at        TIMESTAMPTZ,
    sent_at           TIMESTAMPTZ,
    targeted_count    INTEGER       NOT NULL DEFAULT 0,
    sent_count        INTEGER       NOT NULL DEFAULT 0,
    delivered_count   INTEGER       NOT NULL DEFAULT 0,
    read_count        INTEGER       NOT NULL DEFAULT 0,
    failed_count      INTEGER       NOT NULL DEFAULT 0,
    skipped_count     INTEGER       NOT NULL DEFAULT 0,
    uncertain_count   INTEGER       NOT NULL DEFAULT 0,
    created_by        UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- the list, newest first (keyset on created_at, id)
CREATE INDEX IF NOT EXISTS idx_whatsapp_campaigns_created ON whatsapp_campaigns (created_at DESC, id DESC);
-- one campaign sending at a time per company
CREATE UNIQUE INDEX IF NOT EXISTS uq_whatsapp_campaigns_sending ON whatsapp_campaigns ((status)) WHERE status = 'SENDING';

CREATE TABLE IF NOT EXISTS whatsapp_campaign_recipients (
    campaign_id   UUID          NOT NULL REFERENCES whatsapp_campaigns (id) ON DELETE CASCADE,
    phone         VARCHAR(20)   NOT NULL CONSTRAINT whatsapp_campaign_recipients_phone_check
                                    CHECK (phone ~ '^[0-9]{6,20}$'),
    client_id     UUID          NOT NULL,
    client_name   VARCHAR(200),
    store_name    VARCHAR(200),
    status        VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CONSTRAINT whatsapp_campaign_recipients_status_check
                                    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED', 'UNCERTAIN')),
    wa_message_id VARCHAR(128),
    error         VARCHAR(200),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (campaign_id, phone)
);

-- the next batch: PENDING rows in phone order (index order, LIMIT: never re-scans the rows already processed)
CREATE INDEX IF NOT EXISTS idx_whatsapp_campaign_recipients_pending
    ON whatsapp_campaign_recipients (campaign_id, phone) WHERE status = 'PENDING';
-- the rows left SENDING by a crash (expired after the lease)
CREATE INDEX IF NOT EXISTS idx_whatsapp_campaign_recipients_sending
    ON whatsapp_campaign_recipients (campaign_id, updated_at) WHERE status = 'SENDING';
