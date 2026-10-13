-- V19 — Spec 9-1 completion: « Retours utilisateurs » inbox of the admin dashboard.
--
-- A feedback is « traité » once Toor marks it handled (POST /api/v1/admin/feedback/{id}/handle);
-- /unhandle clears both columns. Nothing is ever deleted. handled_by is the SUPER_ADMIN user id
-- (no FK: public.users rows are never hard-deleted and the admin account is fixed).

ALTER TABLE public.feedback ADD COLUMN IF NOT EXISTS handled_at timestamptz NULL;
ALTER TABLE public.feedback ADD COLUMN IF NOT EXISTS handled_by uuid NULL;

-- Default inbox view (unhandled, newest first) and the sidebar badge (COUNT of unhandled HIGH,
-- GET /api/v1/admin/feedback/count) both read this partial index.
CREATE INDEX IF NOT EXISTS idx_feedback_unhandled_priority_submitted
    ON public.feedback (priority, submitted_at DESC) WHERE handled_at IS NULL;
