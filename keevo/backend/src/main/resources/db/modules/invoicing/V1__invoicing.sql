-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V1 (Story 14.1)
--
-- Tables owned by the « Facturation » module, created inside the tenant schema (Flyway search_path = the tenant
-- schema; history flyway_history_invoicing) only for tenants that use the module — never in public, never copied
-- by TenantSchemaSyncService (no JPA entity).
--
-- Legal content (Cameroon, CGI art. 150 (5)): the seller identity is snapshotted onto the invoice when it is
-- issued; the client identity, the lines (nature, quantity, unit price, VAT treatment, HT / VAT / TTC) and the
-- totals are stored as computed. Amounts are XAF integers (francs). Issued invoices are never deleted.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V2__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

-- Seller profile: a single row (id = 1), edited by the owner; copied onto each invoice at issue.
CREATE TABLE IF NOT EXISTS invoicing_settings (
    id                 SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    legal_name         VARCHAR(200),
    address            VARCHAR(300),
    phone              VARCHAR(30),
    niu                VARCHAR(30),
    rccm               VARCHAR(60),
    vat_registered     BOOLEAN      NOT NULL DEFAULT FALSE,
    vat_rate           NUMERIC(5,2) NOT NULL DEFAULT 19.25 CHECK (vat_rate >= 0 AND vat_rate < 100),
    prices_include_vat BOOLEAN      NOT NULL DEFAULT TRUE,
    payment_details    VARCHAR(1000),
    footer             VARCHAR(1000),
    updated_by         UUID,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS invoices (
    id                     UUID          PRIMARY KEY,
    number                 VARCHAR(20)   UNIQUE,
    status                 VARCHAR(12)   NOT NULL DEFAULT 'DRAFT'
                               CHECK (status IN ('DRAFT', 'ISSUED', 'PAID', 'CANCELLED')),
    issue_date             DATE,
    -- client (free text; client_id reserved for the CRM picker, no foreign key)
    client_id              UUID,
    client_name            VARCHAR(200)  NOT NULL,
    client_niu             VARCHAR(30),
    client_address         VARCHAR(300),
    client_phone           VARCHAR(30),
    -- seller snapshot (set at issue)
    seller_legal_name      VARCHAR(200),
    seller_address         VARCHAR(300),
    seller_phone           VARCHAR(30),
    seller_niu             VARCHAR(30),
    seller_rccm            VARCHAR(60),
    seller_payment_details VARCHAR(1000),
    seller_footer          VARCHAR(1000),
    -- VAT parameters used for the computation (frozen at issue)
    vat_registered         BOOLEAN       NOT NULL,
    vat_rate               NUMERIC(5,2)  NOT NULL,
    prices_include_vat     BOOLEAN       NOT NULL,
    vat_mention            VARCHAR(60),
    total_ht               BIGINT        NOT NULL DEFAULT 0 CHECK (total_ht >= 0),
    total_vat              BIGINT        NOT NULL DEFAULT 0 CHECK (total_vat >= 0),
    total_ttc              BIGINT        NOT NULL DEFAULT 0 CHECK (total_ttc >= 0),
    issued_at              TIMESTAMPTZ,
    paid_at                TIMESTAMPTZ,
    payment_method         VARCHAR(60),
    cancelled_at           TIMESTAMPTZ,
    cancellation_reason    VARCHAR(500),
    created_by             UUID,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CHECK (status = 'DRAFT' OR (number IS NOT NULL AND issue_date IS NOT NULL AND issued_at IS NOT NULL)),
    CHECK (status <> 'PAID' OR paid_at IS NOT NULL),
    CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_invoices_status_created ON invoices (status, created_at);
CREATE INDEX IF NOT EXISTS idx_invoices_created ON invoices (created_at);

CREATE TABLE IF NOT EXISTS invoice_items (
    id            UUID          PRIMARY KEY,
    invoice_id    UUID          NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    position      INT           NOT NULL CHECK (position >= 1),
    description   VARCHAR(500)  NOT NULL,
    quantity      NUMERIC(12,3) NOT NULL CHECK (quantity > 0),
    unit_price    BIGINT        NOT NULL CHECK (unit_price >= 0),
    vat_treatment VARCHAR(12)   NOT NULL CHECK (vat_treatment IN ('STANDARD', 'EXEMPT', 'STATE_BORNE')),
    vat_rate      NUMERIC(5,2)  NOT NULL,
    total_ht      BIGINT        NOT NULL CHECK (total_ht >= 0),
    total_vat     BIGINT        NOT NULL CHECK (total_vat >= 0),
    total_ttc     BIGINT        NOT NULL CHECK (total_ttc >= 0),
    UNIQUE (invoice_id, position)
);

-- Gapless numbering: one counter per calendar year (Africa/Douala), incremented in the issue transaction.
CREATE TABLE IF NOT EXISTS invoice_counters (
    year        INT  PRIMARY KEY,
    last_number INT  NOT NULL CHECK (last_number >= 0)
);
