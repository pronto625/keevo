-- V2__drop_users_tenant_id.sql
-- Remove the dangling tenant_id column from public.users.
-- This column was created by Hibernate ddl-auto=update in Story 1.2 but has never been
-- mapped by any JPA entity since Story 1.7 introduced user_tenant_memberships.
-- Architecture decision: architecture.md#Multi-Tenant Identity Model (2026-03-07).
ALTER TABLE users DROP COLUMN IF EXISTS tenant_id;
