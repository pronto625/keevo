-- Story 20.13 — super-admin users list (Bref GET /users → GET /api/v1/admin/users): keyset pagination on
-- (created_at DESC, id DESC). public.users had no index on created_at; this one serves the list order and its
-- keyset predicate (u.created_at, u.id) < (?, ?) as one index range scan. Additive, no data change.
CREATE INDEX IF NOT EXISTS idx_users_created_at_id ON public.users (created_at DESC, id DESC);
