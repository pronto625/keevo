-- V69 — Story 21.6 (fusion Keevo × Bref, epic 21, FUS-FR28 / FR32): client WhatsApp campaigns of the companies and
-- platform campaigns of the super-admin, STOP / START on the platform number, the platform-number inbox.
--
--   usage metric WHATSAPP_MESSAGES  « Messages WhatsApp »: the messages a company's campaigns send from its own number
--                             count on its usage (19.8). No quota is seeded: counted, not limited, until a super-admin
--                             sets one (offer as data, ADR-05). The metric CHECKs are widened (same list everywhere).
--   whatsapp_campaign_jobs    the work queue of the client campaigns (one row per campaign SCHEDULED or SENDING): the
--                             dispatcher finds the due work of every company in ONE indexed query (next_run_at),
--                             claimed FOR UPDATE SKIP LOCKED with a lease, never by visiting the tenant schemas. A
--                             deleted company drops its rows (CASCADE).
--   platform_whatsapp_*       the inbox of the platform number (Bref ownerId NULL): same shape as the tenant tables of
--                             the whatsapp-cloud module, read only by the super-admin.
--   whatsapp_marketing_opt_outs  numbers (digits, country code included) that answered STOP to the platform number:
--                             skipped by the platform WhatsApp campaigns until they answer START.
--   platform_campaigns        the super-admin campaigns (channels, segment, contents), with the due / in-progress work
--                             found by a partial index on next_run_at.
--   platform_campaign_stats   the counters of a campaign per channel (targeted, sent, delivered, read, failed,
--                             skipped, uncertain), moved by increments (sends, Meta's statuses).
--   platform_campaign_recipients  one row per (campaign, channel, owner): the frozen audience, claimed PENDING →
--                             SENDING before each send (at most once), Meta's message id kept for support. Meta's
--                             delivery statuses move the campaign counters from the platform inbox (campaign_id).
--   platform_campaign_events  the audit of the campaigns (who created, changed, launched, cancelled, deleted; the end of
--                             the send) — kept when a campaign is deleted.
--
-- Platform data, public schema only. Idempotent; set-based.
SET LOCAL lock_timeout = '10s';

-- ── usage metric ───────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.plan_quotas DROP CONSTRAINT IF EXISTS plan_quotas_metric_check;
ALTER TABLE public.plan_quotas ADD CONSTRAINT plan_quotas_metric_check
    CHECK (metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS', 'WHATSAPP_MESSAGES'));
ALTER TABLE public.usage_counters DROP CONSTRAINT IF EXISTS usage_counters_metric_check;
ALTER TABLE public.usage_counters ADD CONSTRAINT usage_counters_metric_check
    CHECK (metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS', 'WHATSAPP_MESSAGES'));
ALTER TABLE public.usage_credits DROP CONSTRAINT IF EXISTS usage_credits_metric_check;
ALTER TABLE public.usage_credits ADD CONSTRAINT usage_credits_metric_check
    CHECK (metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS', 'WHATSAPP_MESSAGES'));
ALTER TABLE public.usage_packs DROP CONSTRAINT IF EXISTS usage_packs_metric_check;
ALTER TABLE public.usage_packs ADD CONSTRAINT usage_packs_metric_check
    CHECK (metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS', 'WHATSAPP_MESSAGES'));
ALTER TABLE public.usage_credit_movements DROP CONSTRAINT IF EXISTS usage_credit_movements_metric_check;
ALTER TABLE public.usage_credit_movements ADD CONSTRAINT usage_credit_movements_metric_check
    CHECK (metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS', 'WHATSAPP_MESSAGES'));
ALTER TABLE public.payment_requests DROP CONSTRAINT IF EXISTS payment_requests_shape_check;
ALTER TABLE public.payment_requests ADD CONSTRAINT payment_requests_shape_check
    CHECK ((kind = 'PLAN' AND plan_code IS NOT NULL AND cycle_code IS NOT NULL AND plan_label IS NOT NULL
            AND cycle_label IS NOT NULL AND months IS NOT NULL AND pack_code IS NULL)
        OR (kind = 'USAGE_PACK' AND pack_code IS NOT NULL AND pack_label IS NOT NULL
            AND pack_metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS', 'WHATSAPP_MESSAGES')
            AND pack_quantity > 0 AND plan_code IS NULL AND cycle_code IS NULL AND months IS NULL));

-- ── client campaigns: work queue ───────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.whatsapp_campaign_jobs (
    campaign_id UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES public.tenants (id) ON DELETE CASCADE,
    next_run_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- the due work of every company, oldest first (one indexed range per tick)
CREATE INDEX IF NOT EXISTS idx_whatsapp_campaign_jobs_due ON public.whatsapp_campaign_jobs (next_run_at);
-- the foreign key (CASCADE on company deletion)
CREATE INDEX IF NOT EXISTS idx_whatsapp_campaign_jobs_tenant ON public.whatsapp_campaign_jobs (tenant_id);

-- ── platform-number inbox ──────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.platform_whatsapp_messages (
    id            UUID          PRIMARY KEY,
    contact_phone VARCHAR(20)   NOT NULL
                      CONSTRAINT platform_whatsapp_messages_phone_check CHECK (contact_phone ~ '^[0-9]{6,20}$'),
    direction     VARCHAR(3)    NOT NULL CONSTRAINT platform_whatsapp_messages_direction_check
                                    CHECK (direction IN ('IN', 'OUT')),
    type          VARCHAR(20)   NOT NULL,
    body          TEXT          CONSTRAINT platform_whatsapp_messages_body_check CHECK (length(body) <= 8192),
    wa_message_id VARCHAR(128)  NOT NULL,
    status        VARCHAR(10)   CONSTRAINT platform_whatsapp_messages_status_check
                                    CHECK (status IN ('SENT', 'DELIVERED', 'READ', 'FAILED')),
    read_at       TIMESTAMPTZ,
    sent_by       UUID,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    campaign_id   UUID
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_platform_whatsapp_messages_wa_id ON public.platform_whatsapp_messages (wa_message_id);
CREATE INDEX IF NOT EXISTS idx_platform_whatsapp_messages_thread
    ON public.platform_whatsapp_messages (contact_phone, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_platform_whatsapp_messages_unread ON public.platform_whatsapp_messages (contact_phone)
    WHERE direction = 'IN' AND read_at IS NULL;

CREATE TABLE IF NOT EXISTS public.platform_whatsapp_conversations (
    contact_phone   VARCHAR(20)  PRIMARY KEY,
    contact_name    VARCHAR(200),
    last_message    VARCHAR(500),
    last_type       VARCHAR(20)  NOT NULL,
    last_direction  VARCHAR(3)   NOT NULL CONSTRAINT platform_whatsapp_conversations_direction_check
                                     CHECK (last_direction IN ('IN', 'OUT')),
    last_at         TIMESTAMPTZ  NOT NULL,
    unread_count    INTEGER      NOT NULL DEFAULT 0
                        CONSTRAINT platform_whatsapp_conversations_unread_check CHECK (unread_count >= 0),
    last_inbound_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_platform_whatsapp_conversations_last_at
    ON public.platform_whatsapp_conversations (last_at DESC, contact_phone);
CREATE INDEX IF NOT EXISTS idx_platform_whatsapp_conversations_unread
    ON public.platform_whatsapp_conversations (unread_count) WHERE unread_count > 0;

CREATE TABLE IF NOT EXISTS public.whatsapp_marketing_opt_outs (
    phone         VARCHAR(20) PRIMARY KEY CONSTRAINT whatsapp_marketing_opt_outs_phone_check
                      CHECK (phone ~ '^[0-9]{6,20}$'),
    opted_out_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── platform campaigns ─────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.platform_campaigns (
    id                UUID          PRIMARY KEY,
    name              VARCHAR(100)  NOT NULL,
    description       VARCHAR(500),
    status            VARCHAR(10)   NOT NULL CONSTRAINT platform_campaigns_status_check
                                        CHECK (status IN ('DRAFT', 'SCHEDULED', 'SENDING', 'SENT', 'CANCELLED')),
    channels          VARCHAR(10)[] NOT NULL DEFAULT '{}',
    title             VARCHAR(100),
    message           VARCHAR(1000),
    segment           JSONB         NOT NULL DEFAULT '{}'::jsonb,
    whatsapp          JSONB,
    scheduled_at      TIMESTAMPTZ,
    next_run_at       TIMESTAMPTZ,
    started_at        TIMESTAMPTZ,
    sent_at           TIMESTAMPTZ,
    targeted_count    INTEGER       NOT NULL DEFAULT 0,
    created_by        UUID          REFERENCES public.users (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- the list, newest first (keyset)
CREATE INDEX IF NOT EXISTS idx_platform_campaigns_created ON public.platform_campaigns (created_at DESC, id DESC);
-- the dispatcher: campaigns due (SCHEDULED) or in progress (SENDING)
CREATE INDEX IF NOT EXISTS idx_platform_campaigns_due ON public.platform_campaigns (next_run_at)
    WHERE status IN ('SCHEDULED', 'SENDING');
CREATE INDEX IF NOT EXISTS idx_platform_campaigns_created_by ON public.platform_campaigns (created_by);

CREATE TABLE IF NOT EXISTS public.platform_campaign_recipients (
    campaign_id   UUID          NOT NULL REFERENCES public.platform_campaigns (id) ON DELETE CASCADE,
    channel       VARCHAR(10)   NOT NULL CONSTRAINT platform_campaign_recipients_channel_check
                                    CHECK (channel IN ('PUSH', 'WHATSAPP', 'SMS', 'EMAIL')),
    user_id       UUID          NOT NULL,
    tenant_id     UUID          NOT NULL,
    address       VARCHAR(255),
    first_name    VARCHAR(100),
    last_name     VARCHAR(100),
    company_name  VARCHAR(200),
    status        VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CONSTRAINT platform_campaign_recipients_status_check
                                    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED',
                                                      'SKIPPED', 'UNCERTAIN')),
    wa_message_id VARCHAR(128),
    error         VARCHAR(200),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (campaign_id, channel, user_id)
);
-- the next batch of a campaign: PENDING rows in key order (index order, LIMIT)
CREATE INDEX IF NOT EXISTS idx_platform_campaign_recipients_pending
    ON public.platform_campaign_recipients (campaign_id, channel, user_id) WHERE status = 'PENDING';
-- the rows left SENDING by a crash (expired after the lease)
CREATE INDEX IF NOT EXISTS idx_platform_campaign_recipients_sending
    ON public.platform_campaign_recipients (campaign_id, updated_at) WHERE status = 'SENDING';

CREATE TABLE IF NOT EXISTS public.platform_campaign_stats (
    campaign_id UUID        NOT NULL REFERENCES public.platform_campaigns (id) ON DELETE CASCADE,
    channel     VARCHAR(10) NOT NULL CONSTRAINT platform_campaign_stats_channel_check
                                CHECK (channel IN ('PUSH', 'WHATSAPP', 'SMS', 'EMAIL')),
    targeted    INTEGER     NOT NULL DEFAULT 0,
    sent        INTEGER     NOT NULL DEFAULT 0,
    delivered   INTEGER     NOT NULL DEFAULT 0,
    read        INTEGER     NOT NULL DEFAULT 0,
    failed      INTEGER     NOT NULL DEFAULT 0,
    skipped     INTEGER     NOT NULL DEFAULT 0,
    uncertain   INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, channel)
);

CREATE TABLE IF NOT EXISTS public.platform_campaign_events (
    id            UUID          PRIMARY KEY,
    campaign_id   UUID          NOT NULL,
    campaign_name VARCHAR(100)  NOT NULL,
    action        VARCHAR(20)   NOT NULL CONSTRAINT platform_campaign_events_action_check
                                    CHECK (action IN ('CREATED', 'UPDATED', 'DELETED', 'SEND_STARTED', 'SCHEDULED',
                                                      'CANCELLED', 'COMPLETED')),
    actor_id      UUID,
    detail        VARCHAR(500),
    occurred_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- the history of one campaign
CREATE INDEX IF NOT EXISTS idx_platform_campaign_events_campaign ON public.platform_campaign_events (campaign_id, occurred_at);
