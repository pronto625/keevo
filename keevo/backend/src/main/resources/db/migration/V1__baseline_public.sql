-- V1__baseline_public.sql
-- Baseline migration: captures the entire public schema as created by Hibernate ddl-auto=update.
-- This migration is idempotent (IF NOT EXISTS) and serves as the starting point for Flyway versioning.
-- 
-- Tables included (28):
--   Global: users, tenants, user_tenant_memberships, refresh_tokens
--   Template (copied to tenant schemas by TenantSchemaSyncService):
--     audit_log, device_tokens, draft_notifications, categories, products,
--     product_suppliers, clients, suppliers, stores, subscriptions,
--     tenant_preferences, stock_levels, stock_movements, stock_transfers,
--     sales, sale_items, day_closures, employees, inventory_sessions,
--     inventory_counts, reports, sync_operations_log, sync_conflicts_log
--   Admin: user_sync_state
--
-- Source: pg_dump --schema-only --no-owner --no-privileges --schema=public (2026-06-17)

-- ═══════════════════════════════════════════════════════════════════════════════
-- TABLES
-- ═══════════════════════════════════════════════════════════════════════════════

-- ── audit_log ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_log (
    id uuid NOT NULL,
    action character varying(80) NOT NULL,
    entity_id uuid NOT NULL,
    entity_type character varying(50) NOT NULL,
    occurred_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL,
    value_after text,
    value_before text
);

-- ── categories ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS categories (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    is_active boolean NOT NULL,
    is_custom boolean NOT NULL,
    name character varying(100) NOT NULL,
    parent_id uuid,
    updated_at timestamp(6) with time zone NOT NULL
);

-- ── clients ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clients (
    id uuid NOT NULL,
    archived boolean NOT NULL,
    created_at timestamp with time zone NOT NULL,
    email character varying(255),
    name character varying(200) NOT NULL,
    notes text,
    phone character varying(30) NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

-- ── day_closures ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS day_closures (
    id uuid NOT NULL,
    actor_id uuid,
    cash_amount integer NOT NULL,
    closed_at timestamp(6) with time zone NOT NULL,
    closure_date date NOT NULL,
    is_automatic boolean NOT NULL,
    momo_amount integer NOT NULL,
    pending_sales_count integer NOT NULL,
    pending_sales_total integer NOT NULL,
    store_id uuid NOT NULL,
    tenant_id character varying(64),
    top_product_id character varying(64),
    top_product_name character varying(255),
    top_product_qty integer NOT NULL,
    total_revenue integer NOT NULL,
    total_sales integer NOT NULL
);

-- ── device_tokens ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS device_tokens (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    device_name character varying(100),
    platform character varying(20) NOT NULL,
    role character varying(20) NOT NULL,
    token character varying(512) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL
);

-- ── draft_notifications ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS draft_notifications (
    id uuid NOT NULL,
    acknowledged boolean NOT NULL,
    actor_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    product_id uuid NOT NULL,
    product_name character varying(255) NOT NULL,
    tenant_id uuid NOT NULL
);

-- ── employees ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS employees (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    first_name character varying(100) NOT NULL,
    last_name character varying(100) NOT NULL,
    password_change_required boolean NOT NULL,
    status character varying(20) NOT NULL,
    store_id uuid NOT NULL,
    user_id uuid NOT NULL
);

-- ── inventory_counts ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inventory_counts (
    id uuid NOT NULL,
    counted_at timestamp with time zone,
    counted_by uuid,
    physical integer,
    product_id uuid NOT NULL,
    product_name character varying(200) NOT NULL,
    session_id uuid NOT NULL,
    theoretical integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    variant_id uuid,
    variant_label character varying(100)
);

-- ── inventory_sessions ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inventory_sessions (
    id uuid NOT NULL,
    cancelled_at timestamp(6) with time zone,
    cancelled_by uuid,
    category_ids jsonb,
    completed_at timestamp(6) with time zone,
    scope character varying(10) NOT NULL,
    started_at timestamp(6) with time zone NOT NULL,
    started_by uuid NOT NULL,
    status character varying(15) NOT NULL,
    store_id uuid NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT inventory_sessions_scope_check CHECK (((scope)::text = ANY ((ARRAY['FULL'::character varying, 'PARTIAL'::character varying])::text[]))),
    CONSTRAINT inventory_sessions_status_check CHECK (((status)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'VALIDATED'::character varying, 'CANCELLED'::character varying])::text[])))
);

-- ── product_suppliers ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS product_suppliers (
    product_id uuid NOT NULL,
    supplier_id uuid NOT NULL
);

-- ── products ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS products (
    id uuid NOT NULL,
    archived boolean NOT NULL,
    buy_price integer NOT NULL,
    category_id uuid,
    created_at timestamp with time zone NOT NULL,
    description text,
    name character varying(200) NOT NULL,
    photo_url character varying(500),
    price integer NOT NULL,
    sku character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    stock_quantity integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    transport_cost integer DEFAULT 0 NOT NULL,
    minimum_threshold integer DEFAULT 0 NOT NULL,
    CONSTRAINT products_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DRAFT'::character varying])::text[])))
);

-- ── refresh_tokens ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    revoked boolean NOT NULL,
    tenant_id character varying(64) NOT NULL,
    token_hash character varying(255) NOT NULL,
    user_id uuid NOT NULL
);

-- ── reports ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS reports (
    id uuid NOT NULL,
    content text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    delivery_attempts integer NOT NULL,
    delivery_status character varying(30) NOT NULL,
    is_automatic boolean NOT NULL,
    last_attempt_at timestamp(6) with time zone,
    report_date date NOT NULL,
    report_type character varying(30) NOT NULL,
    store_id uuid NOT NULL,
    store_name character varying(255),
    tenant_id character varying(64) NOT NULL,
    total_revenue integer NOT NULL,
    total_sales integer NOT NULL,
    actor_id uuid,
    actor_name character varying(255)
);

-- ── sale_items ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sale_items (
    id uuid NOT NULL,
    applied_unit_price integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    product_id uuid NOT NULL,
    product_name character varying(255) NOT NULL,
    quantity integer NOT NULL,
    subtotal integer NOT NULL,
    variant_id uuid,
    sale_id uuid NOT NULL,
    catalogue_unit_price integer NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- ── sales ──────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sales (
    id uuid NOT NULL,
    client_id uuid,
    created_at timestamp(6) with time zone NOT NULL,
    employee_id uuid NOT NULL,
    occurred_at timestamp(6) with time zone,
    payment_mode character varying(30) NOT NULL,
    status character varying(20) NOT NULL,
    store_id uuid NOT NULL,
    total_amount integer NOT NULL,
    discount_amount integer NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- ── stock_levels ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock_levels (
    id uuid NOT NULL,
    product_id uuid NOT NULL,
    quantity integer NOT NULL,
    store_id uuid NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    variant_id uuid,
    minimum_threshold integer NOT NULL
);

-- ── stock_movements ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock_movements (
    id uuid NOT NULL,
    actor_id uuid NOT NULL,
    movement_type character varying(30) NOT NULL,
    notes text,
    occurred_at timestamp with time zone NOT NULL,
    product_id uuid NOT NULL,
    quantity_after integer NOT NULL,
    quantity_before integer NOT NULL,
    quantity_change integer NOT NULL,
    store_id uuid NOT NULL,
    variant_id uuid,
    CONSTRAINT stock_movements_movement_type_check CHECK (((movement_type)::text = ANY ((ARRAY['SALE'::character varying, 'STOCK_ENTRY'::character varying, 'TRANSFER_IN'::character varying, 'TRANSFER_OUT'::character varying, 'ADJUSTMENT'::character varying])::text[])))
);

-- ── stock_transfers ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock_transfers (
    id uuid NOT NULL,
    actor_id uuid NOT NULL,
    destination_store_id uuid NOT NULL,
    notes text,
    occurred_at timestamp with time zone NOT NULL,
    product_id uuid NOT NULL,
    quantity integer NOT NULL,
    source_store_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    variant_id uuid,
    CONSTRAINT stock_transfers_status_check CHECK (((status)::text = ANY ((ARRAY['COMPLETED'::character varying, 'PENDING_SYNC'::character varying, 'CONFLICT'::character varying, 'IN_TRANSIT'::character varying])::text[])))
);

-- ── stores ─────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stores (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    address character varying(255),
    is_active boolean NOT NULL,
    name character varying(100) NOT NULL,
    phone character varying(30),
    type character varying(10) DEFAULT 'STORE'::character varying NOT NULL,
    CONSTRAINT stores_type_check CHECK (((type)::text = ANY ((ARRAY['STORE'::character varying, 'WAREHOUSE'::character varying])::text[])))
);

-- ── subscriptions ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS subscriptions (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone,
    max_employees integer NOT NULL,
    max_products integer NOT NULL,
    max_stores integer NOT NULL,
    plan_type character varying(20) NOT NULL,
    status character varying(20) NOT NULL
);

-- ── suppliers ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS suppliers (
    id uuid NOT NULL,
    archived boolean NOT NULL,
    created_at timestamp with time zone NOT NULL,
    email character varying(255),
    name character varying(200) NOT NULL,
    phone character varying(30) NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

-- ── sync_conflicts_log ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sync_conflicts_log (
    id character varying(36) NOT NULL,
    actor_id character varying(36),
    conflict_data jsonb,
    conflict_type character varying(30) NOT NULL,
    entity_id character varying(36),
    entity_type character varying(50),
    operation_id character varying(36) NOT NULL,
    operation_type character varying(50) NOT NULL,
    resolved_at timestamp(6) with time zone NOT NULL,
    strategy character varying(30) NOT NULL
);

-- ── sync_operations_log ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sync_operations_log (
    id character varying(36) NOT NULL,
    client_timestamp timestamp(6) with time zone,
    entity_id character varying(36),
    error_reason text,
    operation_type character varying(50) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    status character varying(20) NOT NULL
);

-- ── tenant_preferences ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tenant_preferences (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    eod_report_time time(6) without time zone NOT NULL,
    sector_type character varying(30),
    stock_alert_enabled boolean NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    eod_report_channel character varying(20) NOT NULL,
    eod_report_enabled boolean NOT NULL,
    inventory_report_channel character varying(20) NOT NULL,
    inventory_report_enabled boolean NOT NULL,
    stock_alert_channel character varying(20) NOT NULL,
    weekly_report_channel character varying(20) NOT NULL,
    weekly_report_day integer NOT NULL,
    weekly_report_enabled boolean NOT NULL,
    weekly_report_time time(6) without time zone NOT NULL,
    trend_notification_enabled boolean NOT NULL
);

-- ── tenants ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tenants (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    code character varying(10) NOT NULL,
    max_employees integer NOT NULL,
    max_products integer NOT NULL,
    max_stores integer NOT NULL,
    plan_type character varying(20) NOT NULL,
    schema_name character varying(15) NOT NULL,
    status character varying(20) NOT NULL,
    name character varying(100),
    deletion_scheduled_at timestamp(6) with time zone
);

-- ── user_sync_state ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_sync_state (
    device_id character varying(36) NOT NULL,
    user_id uuid NOT NULL,
    tenant_id character varying(100),
    last_push_at timestamp with time zone,
    last_pull_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- ── user_tenant_memberships ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_tenant_memberships (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    is_active boolean NOT NULL,
    role character varying(30) NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL
);

-- ── users ──────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    is_active boolean NOT NULL,
    failed_attempts integer NOT NULL,
    locked_until timestamp(6) with time zone,
    password_hash character varying(255) NOT NULL,
    phone_number character varying(20) NOT NULL,
    role character varying(20) NOT NULL,
    tenant_id uuid
);

-- ═══════════════════════════════════════════════════════════════════════════════
-- PRIMARY KEYS
-- ═══════════════════════════════════════════════════════════════════════════════

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'audit_log_pkey') THEN
        ALTER TABLE audit_log ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'categories_pkey') THEN
        ALTER TABLE categories ADD CONSTRAINT categories_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'clients_pkey') THEN
        ALTER TABLE clients ADD CONSTRAINT clients_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'day_closures_pkey') THEN
        ALTER TABLE day_closures ADD CONSTRAINT day_closures_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'device_tokens_pkey') THEN
        ALTER TABLE device_tokens ADD CONSTRAINT device_tokens_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'draft_notifications_pkey') THEN
        ALTER TABLE draft_notifications ADD CONSTRAINT draft_notifications_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'employees_pkey') THEN
        ALTER TABLE employees ADD CONSTRAINT employees_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'inventory_counts_pkey') THEN
        ALTER TABLE inventory_counts ADD CONSTRAINT inventory_counts_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'inventory_sessions_pkey') THEN
        ALTER TABLE inventory_sessions ADD CONSTRAINT inventory_sessions_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'product_suppliers_pkey') THEN
        ALTER TABLE product_suppliers ADD CONSTRAINT product_suppliers_pkey PRIMARY KEY (product_id, supplier_id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'products_pkey') THEN
        ALTER TABLE products ADD CONSTRAINT products_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'refresh_tokens_pkey') THEN
        ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'reports_pkey') THEN
        ALTER TABLE reports ADD CONSTRAINT reports_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'sale_items_pkey') THEN
        ALTER TABLE sale_items ADD CONSTRAINT sale_items_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'sales_pkey') THEN
        ALTER TABLE sales ADD CONSTRAINT sales_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'stock_levels_pkey') THEN
        ALTER TABLE stock_levels ADD CONSTRAINT stock_levels_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'stock_movements_pkey') THEN
        ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'stock_transfers_pkey') THEN
        ALTER TABLE stock_transfers ADD CONSTRAINT stock_transfers_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'stores_pkey') THEN
        ALTER TABLE stores ADD CONSTRAINT stores_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'subscriptions_pkey') THEN
        ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'suppliers_pkey') THEN
        ALTER TABLE suppliers ADD CONSTRAINT suppliers_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'sync_conflicts_log_pkey') THEN
        ALTER TABLE sync_conflicts_log ADD CONSTRAINT sync_conflicts_log_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'sync_operations_log_pkey') THEN
        ALTER TABLE sync_operations_log ADD CONSTRAINT sync_operations_log_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'tenant_preferences_pkey') THEN
        ALTER TABLE tenant_preferences ADD CONSTRAINT tenant_preferences_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'tenants_pkey') THEN
        ALTER TABLE tenants ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_sync_state_pkey') THEN
        ALTER TABLE user_sync_state ADD CONSTRAINT user_sync_state_pkey PRIMARY KEY (device_id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_tenant_memberships_pkey') THEN
        ALTER TABLE user_tenant_memberships ADD CONSTRAINT user_tenant_memberships_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'users_pkey') THEN
        ALTER TABLE users ADD CONSTRAINT users_pkey PRIMARY KEY (id);
    END IF;
END $$;

-- ═══════════════════════════════════════════════════════════════════════════════
-- UNIQUE CONSTRAINTS
-- ═══════════════════════════════════════════════════════════════════════════════

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_tenants_schema_name') THEN
        ALTER TABLE tenants ADD CONSTRAINT uk_tenants_schema_name UNIQUE (schema_name);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_tenants_code') THEN
        ALTER TABLE tenants ADD CONSTRAINT uk_tenants_code UNIQUE (code);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_device_tokens_token') THEN
        ALTER TABLE device_tokens ADD CONSTRAINT uk_device_tokens_token UNIQUE (token);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_users_phone_number') THEN
        ALTER TABLE users ADD CONSTRAINT uk_users_phone_number UNIQUE (phone_number);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_user_tenant_memberships_user_tenant') THEN
        ALTER TABLE user_tenant_memberships ADD CONSTRAINT uk_user_tenant_memberships_user_tenant UNIQUE (user_id, tenant_id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_refresh_tokens_token_hash') THEN
        ALTER TABLE refresh_tokens ADD CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash);
    END IF;
END $$;

-- ═══════════════════════════════════════════════════════════════════════════════
-- INDEXES
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE INDEX IF NOT EXISTS idx_user_sync_state_user ON user_sync_state USING btree (user_id);

-- ═══════════════════════════════════════════════════════════════════════════════
-- FOREIGN KEYS
-- ═══════════════════════════════════════════════════════════════════════════════

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_sale_items_sale_id') THEN
        ALTER TABLE sale_items ADD CONSTRAINT fk_sale_items_sale_id FOREIGN KEY (sale_id) REFERENCES sales(id);
    END IF;
END $$;
