-- V13 — Story v1s-13-12: idempotent (re)creation of the V1 inline CHECK constraints.
--
-- V1 declared these CHECK constraints inline in `CREATE TABLE IF NOT EXISTS`. On databases
-- whose public tables had already been created by Hibernate (ddl-auto=update era) the CREATE
-- was skipped, so the constraints were never added — or a Hibernate-generated constraint with
-- the same name carries a stale value list (e.g. stock_transfers_status_check without
-- IN_TRANSIT). Each block below therefore:
--   * skips the table if it does not exist;
--   * drops the constraint by name if present, then re-adds it with the current value list
--     (re-running the script always ends with exactly one constraint per name);
--   * if existing rows violate the CHECK, re-adds it NOT VALID (new writes are still checked)
--     and RAISEs a WARNING, so a legacy row never blocks deploy.
-- stock_movements_movement_type_check now includes SALE_CANCELLED (Story v1s-13-5).
-- Values mirror the Java enums: InventoryScope, InventorySessionStatus, ProductStatus,
-- MovementType, StockTransfer.TransferStatus, StoreType.
-- Tenant schemas are NOT touched (TenantSchemaProvisioner DDL owns their constraints).

-- ── inventory_sessions.scope ──
DO $$
BEGIN
    IF to_regclass('public.inventory_sessions') IS NULL THEN
        RETURN;
    END IF;
    ALTER TABLE public.inventory_sessions DROP CONSTRAINT IF EXISTS inventory_sessions_scope_check;
    BEGIN
        ALTER TABLE public.inventory_sessions ADD CONSTRAINT inventory_sessions_scope_check CHECK (scope IN ('FULL', 'PARTIAL'));
    EXCEPTION WHEN check_violation THEN
        ALTER TABLE public.inventory_sessions ADD CONSTRAINT inventory_sessions_scope_check CHECK (scope IN ('FULL', 'PARTIAL')) NOT VALID;
        RAISE WARNING 'V13: public.inventory_sessions has rows violating inventory_sessions_scope_check; constraint added NOT VALID';
    END;
END $$;

-- ── inventory_sessions.status ──
DO $$
BEGIN
    IF to_regclass('public.inventory_sessions') IS NULL THEN
        RETURN;
    END IF;
    ALTER TABLE public.inventory_sessions DROP CONSTRAINT IF EXISTS inventory_sessions_status_check;
    BEGIN
        ALTER TABLE public.inventory_sessions ADD CONSTRAINT inventory_sessions_status_check CHECK (status IN ('IN_PROGRESS', 'VALIDATED', 'CANCELLED'));
    EXCEPTION WHEN check_violation THEN
        ALTER TABLE public.inventory_sessions ADD CONSTRAINT inventory_sessions_status_check CHECK (status IN ('IN_PROGRESS', 'VALIDATED', 'CANCELLED')) NOT VALID;
        RAISE WARNING 'V13: public.inventory_sessions has rows violating inventory_sessions_status_check; constraint added NOT VALID';
    END;
END $$;

-- ── products.status ──
DO $$
BEGIN
    IF to_regclass('public.products') IS NULL THEN
        RETURN;
    END IF;
    ALTER TABLE public.products DROP CONSTRAINT IF EXISTS products_status_check;
    BEGIN
        ALTER TABLE public.products ADD CONSTRAINT products_status_check CHECK (status IN ('ACTIVE', 'DRAFT'));
    EXCEPTION WHEN check_violation THEN
        ALTER TABLE public.products ADD CONSTRAINT products_status_check CHECK (status IN ('ACTIVE', 'DRAFT')) NOT VALID;
        RAISE WARNING 'V13: public.products has rows violating products_status_check; constraint added NOT VALID';
    END;
END $$;

-- ── stock_movements.movement_type ──
DO $$
BEGIN
    IF to_regclass('public.stock_movements') IS NULL THEN
        RETURN;
    END IF;
    ALTER TABLE public.stock_movements DROP CONSTRAINT IF EXISTS stock_movements_movement_type_check;
    BEGIN
        ALTER TABLE public.stock_movements ADD CONSTRAINT stock_movements_movement_type_check CHECK (movement_type IN ('SALE', 'STOCK_ENTRY', 'TRANSFER_IN', 'TRANSFER_OUT', 'ADJUSTMENT', 'SALE_CANCELLED'));
    EXCEPTION WHEN check_violation THEN
        ALTER TABLE public.stock_movements ADD CONSTRAINT stock_movements_movement_type_check CHECK (movement_type IN ('SALE', 'STOCK_ENTRY', 'TRANSFER_IN', 'TRANSFER_OUT', 'ADJUSTMENT', 'SALE_CANCELLED')) NOT VALID;
        RAISE WARNING 'V13: public.stock_movements has rows violating stock_movements_movement_type_check; constraint added NOT VALID';
    END;
END $$;

-- ── stock_transfers.status ──
DO $$
BEGIN
    IF to_regclass('public.stock_transfers') IS NULL THEN
        RETURN;
    END IF;
    ALTER TABLE public.stock_transfers DROP CONSTRAINT IF EXISTS stock_transfers_status_check;
    BEGIN
        ALTER TABLE public.stock_transfers ADD CONSTRAINT stock_transfers_status_check CHECK (status IN ('COMPLETED', 'PENDING_SYNC', 'CONFLICT', 'IN_TRANSIT'));
    EXCEPTION WHEN check_violation THEN
        ALTER TABLE public.stock_transfers ADD CONSTRAINT stock_transfers_status_check CHECK (status IN ('COMPLETED', 'PENDING_SYNC', 'CONFLICT', 'IN_TRANSIT')) NOT VALID;
        RAISE WARNING 'V13: public.stock_transfers has rows violating stock_transfers_status_check; constraint added NOT VALID';
    END;
END $$;

-- ── stores.type ──
DO $$
BEGIN
    IF to_regclass('public.stores') IS NULL THEN
        RETURN;
    END IF;
    ALTER TABLE public.stores DROP CONSTRAINT IF EXISTS stores_type_check;
    BEGIN
        ALTER TABLE public.stores ADD CONSTRAINT stores_type_check CHECK (type IN ('STORE', 'WAREHOUSE'));
    EXCEPTION WHEN check_violation THEN
        ALTER TABLE public.stores ADD CONSTRAINT stores_type_check CHECK (type IN ('STORE', 'WAREHOUSE')) NOT VALID;
        RAISE WARNING 'V13: public.stores has rows violating stores_type_check; constraint added NOT VALID';
    END;
END $$;
