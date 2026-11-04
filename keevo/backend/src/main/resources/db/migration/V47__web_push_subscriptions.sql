-- V47 — Story 19.9b (fusion Keevo × Bref, epic 19): browser notifications (Web Push, VAPID), port of Bref's
-- web_push_subscription.
--
--   web_push_subscriptions  one row per browser push subscription of a user (public.users id; users are global, a
--                           browser receives the notifications of every company of the user it is linked to).
--                           endpoint is unique: subscribing again upserts it. Rows answered 404/410 by the push
--                           service are deleted at send time.
--   ix_web_push_subscriptions_user  recipients' subscriptions of one notification (WHERE user_id = ANY(?)).
--
-- Additive and idempotent; public schema only.
CREATE TABLE IF NOT EXISTS public.web_push_subscriptions (
    id         UUID          PRIMARY KEY,
    user_id    UUID          NOT NULL,
    endpoint   VARCHAR(2048) NOT NULL,
    p256dh     VARCHAR(200)  NOT NULL,
    auth       VARCHAR(100)  NOT NULL,
    user_agent VARCHAR(500)  NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_web_push_subscriptions_endpoint UNIQUE (endpoint)
);

CREATE INDEX IF NOT EXISTS ix_web_push_subscriptions_user ON public.web_push_subscriptions (user_id);
