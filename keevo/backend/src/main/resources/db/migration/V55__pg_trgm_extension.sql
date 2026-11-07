-- Story 20.6 — the pg_trgm extension (trigram operator classes), installed once in the public schema, so that the
-- tenant module script crm V3 can build GIN trigram indexes for the client search (lower(name) / phone LIKE '%q%').
-- Tenant scripts run without public in their search_path: they name the operator class public.gin_trgm_ops.
-- pg_trgm is a trusted extension (PostgreSQL 13+): the database owner may install it without superuser rights.
-- Idempotent, no data touched.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
