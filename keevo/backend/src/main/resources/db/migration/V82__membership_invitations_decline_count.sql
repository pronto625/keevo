-- V82 — Story 19.6b, reprise 11/11/2026 (user decision): the 30-day wait before a company may invite the same person
-- again applies only after her SECOND refusal. One refusal → the company may invite again at once (still within the
-- daily cap of 20 invitations per company and one message per person per 24 h).
--
--   decline_count  refusals of the person for this company (incremented by a refusal, reset by an acceptance). The
--                  renewal upsert leaves a DECLINED row untouched only when decline_count >= 2 and the refusal is
--                  younger than 30 days. Rows already DECLINED count as one refusal (they become re-invitable).
--
-- Platform data, public schema only. Idempotent.
SET LOCAL lock_timeout = '10s';

ALTER TABLE public.membership_invitations
    ADD COLUMN IF NOT EXISTS decline_count SMALLINT NOT NULL DEFAULT 0;

UPDATE public.membership_invitations SET decline_count = 1 WHERE status = 'DECLINED' AND decline_count = 0;
