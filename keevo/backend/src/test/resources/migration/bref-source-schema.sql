-- Story 24.2 — the Bref tables the identity import reads ("user", shop), as api-bref creates them (structure of
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
