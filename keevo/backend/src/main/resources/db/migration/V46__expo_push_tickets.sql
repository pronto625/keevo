-- V46 — Story 19.9b (fusion Keevo × Bref, epic 19): Expo push receipts.
--
--   expo_push_tickets  one row per Expo ticket accepted ("ok") by POST /push/send: the ticket id, the device token it
--                      was sent to and the tenant schema holding that token. ExpoReceiptJob (every 15 min) reads the
--                      tickets older than 15 min by batches of 1000, asks Expo for their receipts, deletes the
--                      DeviceNotRegistered tokens (one statement per tenant schema), then deletes the processed rows.
--                      Tickets older than 24 h are deleted unread (Expo keeps receipts 24 h).
--   ix_expo_push_tickets_created_id  keyset batch read (WHERE created_at < ? AND (created_at, ticket_id) > (?, ?)
--                                    ORDER BY created_at, ticket_id LIMIT 1000: one send stamps many tickets with the same
--                                    created_at) and the 24 h purge.
--
-- Additive and idempotent; public schema only.
CREATE TABLE IF NOT EXISTS public.expo_push_tickets (
    ticket_id     VARCHAR(100) PRIMARY KEY,
    token         VARCHAR(512) NOT NULL,
    tenant_schema VARCHAR(63)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_expo_push_tickets_created_id ON public.expo_push_tickets (created_at, ticket_id);
