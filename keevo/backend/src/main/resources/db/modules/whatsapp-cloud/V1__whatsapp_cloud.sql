-- ─────────────────────────────────────────────────────────────────────────────
-- Module whatsapp-cloud — V1 (Story 21.5, fusion Keevo × Bref, FUS-FR28)
--
-- The company's WhatsApp Business inbox and message templates (Bref whatsapp_chat_message, owner_whatsapp_template),
-- created inside the tenant schema (Flyway search_path = the tenant schema; history flyway_history_whatsapp_cloud) only
-- for tenants that use the module — never in public, no JPA entity. Applied lazily on the first gated call
-- (ModuleProvisioner.ensureProvisioned) and before the webhook writes.
--
-- whatsapp_messages       every message of the connected number, received (IN) or sent (OUT). wa_message_id is
--                         Meta's id: unique, so a webhook delivered again inserts nothing (idempotent consumer).
-- whatsapp_conversations  one row per contact maintained on write (last message, unread count, last inbound time of
--                         the 24-hour window): the list is an index scan, never an aggregate over the messages.
-- whatsapp_templates      the company's message templates (DRAFT until submitted to Meta, then Meta's review status);
--                         system = true for the templates Keevo creates on the account (bref_facture).
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V2__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS whatsapp_messages (
    id            UUID          PRIMARY KEY,
    contact_phone VARCHAR(20)   NOT NULL CONSTRAINT whatsapp_messages_phone_check CHECK (contact_phone ~ '^[0-9]{6,20}$'),
    direction     VARCHAR(3)    NOT NULL CONSTRAINT whatsapp_messages_direction_check CHECK (direction IN ('IN', 'OUT')),
    type          VARCHAR(20)   NOT NULL,
    body          TEXT          CONSTRAINT whatsapp_messages_body_check CHECK (length(body) <= 8192),
    wa_message_id VARCHAR(128)  NOT NULL,
    status        VARCHAR(10)   CONSTRAINT whatsapp_messages_status_check
                                    CHECK (status IN ('SENT', 'DELIVERED', 'READ', 'FAILED')),
    read_at       TIMESTAMPTZ,
    sent_by       UUID,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- idempotent insert (ON CONFLICT) and the status updates of the webhook
CREATE UNIQUE INDEX IF NOT EXISTS uq_whatsapp_messages_wa_id ON whatsapp_messages (wa_message_id);
-- one conversation, newest first (keyset on created_at, id)
CREATE INDEX IF NOT EXISTS idx_whatsapp_messages_thread ON whatsapp_messages (contact_phone, created_at DESC, id DESC);
-- the received messages not read yet of one conversation (marked read when it is opened)
CREATE INDEX IF NOT EXISTS idx_whatsapp_messages_unread ON whatsapp_messages (contact_phone)
    WHERE direction = 'IN' AND read_at IS NULL;

CREATE TABLE IF NOT EXISTS whatsapp_conversations (
    contact_phone   VARCHAR(20)  PRIMARY KEY,
    contact_name    VARCHAR(200),
    last_message    VARCHAR(500),
    last_type       VARCHAR(20)  NOT NULL,
    last_direction  VARCHAR(3)   NOT NULL CONSTRAINT whatsapp_conversations_direction_check
                                     CHECK (last_direction IN ('IN', 'OUT')),
    last_at         TIMESTAMPTZ  NOT NULL,
    unread_count    INTEGER      NOT NULL DEFAULT 0 CONSTRAINT whatsapp_conversations_unread_check CHECK (unread_count >= 0),
    last_inbound_at TIMESTAMPTZ
);

-- the conversation list, most recent first
CREATE INDEX IF NOT EXISTS idx_whatsapp_conversations_last_at ON whatsapp_conversations (last_at DESC, contact_phone);
-- the unread badge (sum over the conversations that have unread messages only)
CREATE INDEX IF NOT EXISTS idx_whatsapp_conversations_unread ON whatsapp_conversations (unread_count)
    WHERE unread_count > 0;

CREATE TABLE IF NOT EXISTS whatsapp_templates (
    id               UUID          PRIMARY KEY,
    name             VARCHAR(512)  NOT NULL CONSTRAINT whatsapp_templates_name_check
                                       CHECK (name ~ '^[a-z0-9_]+$' AND length(name) <= 512),
    language         VARCHAR(10)   NOT NULL,
    category         VARCHAR(20)   NOT NULL CONSTRAINT whatsapp_templates_category_check
                                       CHECK (category IN ('MARKETING', 'UTILITY')),
    header_format    VARCHAR(10)   NOT NULL DEFAULT 'NONE' CONSTRAINT whatsapp_templates_header_check
                                       CHECK (header_format IN ('NONE', 'TEXT', 'IMAGE', 'VIDEO', 'DOCUMENT')),
    header_text      VARCHAR(60),
    header_handle    TEXT,
    body_text        VARCHAR(1024) NOT NULL,
    body_examples    TEXT[]        NOT NULL DEFAULT '{}',
    footer_text      VARCHAR(60),
    status           VARCHAR(10)   NOT NULL CONSTRAINT whatsapp_templates_status_check
                                       CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'PAUSED', 'DISABLED')),
    rejected_reason  VARCHAR(200),
    submit_error     VARCHAR(500),
    meta_template_id VARCHAR(64),
    system           BOOLEAN       NOT NULL DEFAULT false,
    created_by       UUID,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- one template per name and language (Meta's rule); the lookups by name of the invoice template
CREATE UNIQUE INDEX IF NOT EXISTS uq_whatsapp_templates_name_language ON whatsapp_templates (name, language);
-- the review results of the webhook (by Meta's id)
CREATE INDEX IF NOT EXISTS idx_whatsapp_templates_meta_id ON whatsapp_templates (meta_template_id);
