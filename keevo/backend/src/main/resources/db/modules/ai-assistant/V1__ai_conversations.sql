-- ─────────────────────────────────────────────────────────────────────────────
-- Module ai-assistant — V1 (Story 21.4, fusion Keevo × Bref, FUS-FR27 / FR39)
--
-- The assistant's saved conversations (Bref « Bref Point » ai_conversation), created inside the tenant schema (Flyway
-- search_path = the tenant schema; history flyway_history_ai_assistant) only for tenants that use the module — never
-- in public, no JPA entity. Applied lazily on the first gated call (ModuleProvisioner.ensureProvisioned).
--
-- One row per conversation of one user (user_id = the member's user id): the messages shown in the chat and the
-- history sent to the model are opaque JSON arrays written by the app (Bref shapes), bounded by the API.
--
-- Index: the list of a user's conversations, most recent first (and the per-user count of the creation cap).
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V2__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS ai_conversations (
    id            UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL,
    title         VARCHAR(120) NOT NULL DEFAULT '',
    message_count INTEGER      NOT NULL DEFAULT 0
                      CONSTRAINT ai_conversations_message_count_check CHECK (message_count BETWEEN 0 AND 10000),
    messages      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    ai_history    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_conversations_user_updated
    ON ai_conversations (user_id, updated_at DESC, id);
