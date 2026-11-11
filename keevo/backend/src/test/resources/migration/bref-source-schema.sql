-- Story 24.2 — the Bref tables the import reads ("user", shop; product, customer since 24.3), as api-bref creates them (structure of
-- api-bref origin/develop 6faa8b4, taken from a pg_dump of a Bref database: DDL only, no row). The tests insert
-- synthetic rows (random ids and phones), never real data.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE public."user" (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    email character varying,
    password character varying,
    "firstName" character varying,
    "lastName" character varying,
    phone character varying,
    role character varying DEFAULT 'seller'::character varying NOT NULL,
    "shopId" character varying,
    "isActive" boolean DEFAULT true NOT NULL,
    "totalSales" numeric(14,2) DEFAULT '0'::numeric NOT NULL,
    "invoicesCreated" integer DEFAULT 0 NOT NULL,
    username character varying,
    "mustChangePassword" boolean DEFAULT false NOT NULL,
    "isVerified" boolean DEFAULT false NOT NULL,
    "socialId" character varying,
    provider character varying DEFAULT 'email'::character varying,
    plan character varying DEFAULT 'free'::character varying NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "deletedAt" timestamp without time zone,
    "scheduledDeletionAt" timestamp with time zone,
    "whatsappMarketingOptOutAt" timestamp without time zone,
    "tokensValidAfter" timestamp without time zone,
    "emailVerified" boolean DEFAULT false NOT NULL
);

CREATE TABLE public.shop (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "userId" uuid NOT NULL,
    name character varying NOT NULL,
    address character varying NOT NULL,
    category character varying NOT NULL,
    description character varying,
    phone character varying,
    country character varying DEFAULT 'CM'::character varying NOT NULL,
    "isActive" boolean DEFAULT true NOT NULL,
    "totalSales" numeric(14,2) DEFAULT '0'::numeric NOT NULL,
    "monthSales" numeric(14,2) DEFAULT '0'::numeric NOT NULL,
    invoices integer DEFAULT 0 NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "deletedAt" timestamp without time zone,
    "photoUrl" character varying,
    niu character varying,
    rccm character varying
);

ALTER TABLE ONLY public."user" ADD CONSTRAINT "PK_cace4a159ff9f2512dd42373760" PRIMARY KEY (id);
ALTER TABLE ONLY public.shop ADD CONSTRAINT "PK_ad47b7c6121fe31cb4b05438e44" PRIMARY KEY (id);
ALTER TABLE ONLY public.shop
    ADD CONSTRAINT "FK_e8f0a5cb5967931a347c31619b6" FOREIGN KEY ("userId") REFERENCES public."user"(id);

-- Story 24.3 — the catalogue and the customers (same origin: pg_dump of the 03/11 Bref database, DDL only)
CREATE TABLE public.customer (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "shopId" uuid NOT NULL,
    name character varying NOT NULL,
    phone character varying NOT NULL,
    email character varying,
    "totalInvoices" integer DEFAULT 0 NOT NULL,
    "totalSpent" numeric(14,2) DEFAULT '0'::numeric NOT NULL,
    "lastPurchaseAt" timestamp without time zone,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "marketingConsentAt" timestamp without time zone,
    "marketingOptOutAt" timestamp without time zone,
    type character varying,
    "marketingConsentById" uuid,
    nui character varying,
    rccm character varying,
    address character varying,
    "contactName" character varying,
    "birthDate" character varying,
    notes text,
    website character varying
);

CREATE TABLE public.product (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "shopId" character varying NOT NULL,
    name character varying NOT NULL,
    "nameKey" character varying NOT NULL,
    "productKey" character varying NOT NULL,
    reference character varying,
    "unitPrice" numeric(14,2) DEFAULT '0'::numeric NOT NULL,
    "priceManual" boolean DEFAULT false NOT NULL,
    "timesSold" integer DEFAULT 0 NOT NULL,
    "totalQuantity" numeric(16,3) DEFAULT 0 NOT NULL,
    "totalRevenue" numeric(14,2) DEFAULT '0'::numeric NOT NULL,
    "lastSoldAt" timestamp without time zone,
    "isArchived" boolean DEFAULT false NOT NULL,
    "mergedIntoId" character varying,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.customer ADD CONSTRAINT "PK_a7a13f4cacb744524e44dfdad32" PRIMARY KEY (id);
ALTER TABLE ONLY public.product ADD CONSTRAINT "PK_bebc9158e480b949565b4dc7a82" PRIMARY KEY (id);
CREATE UNIQUE INDEX "IDX_34eea9a6112343a9d675696e36" ON public.customer USING btree ("shopId", phone);
CREATE UNIQUE INDEX "IDX_product_shopId_productKey" ON public.product USING btree ("shopId", "productKey");
