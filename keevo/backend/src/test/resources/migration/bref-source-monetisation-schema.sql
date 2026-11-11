-- Story 24.5 — the Bref monetisation tables the import reads (subscription, plan_payment_request, apple_iap_transaction,
-- revenuecat_webhook_event (develop), account_credit, sms_recharge_request, usage_counter), as api-bref creates them:
-- DDL only, from a pg_dump of the 03/11 Bref database (no row). Applied after bref-source-schema.sql. The tests insert
-- synthetic rows (random ids), never real data.


CREATE TABLE public.account_credit (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "ownerId" uuid NOT NULL,
    amount integer DEFAULT 0 NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.apple_iap_transaction (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "ownerId" character varying NOT NULL,
    "originalTransactionId" character varying NOT NULL,
    "transactionId" character varying NOT NULL,
    "productId" character varying NOT NULL,
    "bundleId" character varying NOT NULL,
    environment character varying NOT NULL,
    status character varying DEFAULT 'active'::character varying NOT NULL,
    "purchaseDate" timestamp with time zone NOT NULL,
    "expiresDate" timestamp with time zone,
    "revocationReason" integer,
    "revocationDate" timestamp with time zone,
    "subscriptionId" character varying,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.plan_payment_request (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "ownerId" uuid NOT NULL,
    "targetPlan" character varying NOT NULL,
    "targetBillingCycle" character varying,
    amount integer DEFAULT 0 NOT NULL,
    "creditApplied" integer DEFAULT 0 NOT NULL,
    "amountDue" integer DEFAULT 0 NOT NULL,
    currency character varying DEFAULT 'XAF'::character varying NOT NULL,
    status character varying DEFAULT 'pending'::character varying NOT NULL,
    "paymentMethod" character varying,
    "startsAt" timestamp without time zone,
    "endsAt" timestamp without time zone,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "paymentProvider" character varying,
    "providerTransactionRef" character varying,
    "providerStatus" character varying,
    "providerMetadata" jsonb
);

CREATE TABLE public.revenuecat_webhook_event (
    id character varying(128) NOT NULL,
    type character varying(64) NOT NULL,
    "subjectKey" character varying(255),
    "eventTimestampMs" bigint DEFAULT 0 NOT NULL,
    "processedAt" timestamp without time zone,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.sms_recharge_request (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "userId" uuid NOT NULL,
    "smsQuantity" integer NOT NULL,
    "amountDue" integer NOT NULL,
    currency character varying DEFAULT 'XAF'::character varying NOT NULL,
    status character varying DEFAULT 'pending'::character varying NOT NULL,
    "paymentMethod" character varying NOT NULL,
    phone character varying,
    "cardNumber" character varying,
    "completedAt" timestamp without time zone,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.subscription (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "ownerId" uuid NOT NULL,
    plan character varying DEFAULT 'free'::character varying NOT NULL,
    status character varying DEFAULT 'active'::character varying NOT NULL,
    "billingCycle" character varying,
    "startedAt" timestamp without time zone,
    "endsAt" timestamp without time zone,
    "amountPaid" integer DEFAULT 0 NOT NULL,
    "creditUsed" integer DEFAULT 0 NOT NULL,
    "paymentMethod" character varying,
    "appleOriginalTransactionId" character varying,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "gracePeriodEndsAt" timestamp without time zone
);

CREATE TABLE public.usage_counter (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "ownerId" uuid NOT NULL,
    metric character varying NOT NULL,
    "periodMonth" character varying NOT NULL,
    used integer DEFAULT 0 NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.apple_iap_transaction
    ADD CONSTRAINT "PK_0257eaba24806919be1b7074f5f" PRIMARY KEY (id);

ALTER TABLE ONLY public.account_credit
    ADD CONSTRAINT "PK_19f546879d0b6727091cef111c1" PRIMARY KEY (id);

ALTER TABLE ONLY public.sms_recharge_request
    ADD CONSTRAINT "PK_2252ed7f980cf63c36aeaacd2dc" PRIMARY KEY (id);

ALTER TABLE ONLY public.plan_payment_request
    ADD CONSTRAINT "PK_4bb5eee835b42e14d327215d4e2" PRIMARY KEY (id);

ALTER TABLE ONLY public.subscription
    ADD CONSTRAINT "PK_8c3e00ebd02103caa1174cd5d9d" PRIMARY KEY (id);

ALTER TABLE ONLY public.usage_counter
    ADD CONSTRAINT "PK_8e9a3c5d8b61c0dcfa15720d2a0" PRIMARY KEY (id);

ALTER TABLE ONLY public.revenuecat_webhook_event
    ADD CONSTRAINT "PK_revenuecat_webhook_event_id" PRIMARY KEY (id);

ALTER TABLE ONLY public.usage_counter
    ADD CONSTRAINT "UQ_0b17aa5bb36054566fc51e6f21b" UNIQUE ("ownerId", metric, "periodMonth");

ALTER TABLE ONLY public.account_credit
    ADD CONSTRAINT "UQ_a2eef6b2ccf70b4e0e77832c9cf" UNIQUE ("ownerId");

ALTER TABLE ONLY public.apple_iap_transaction
    ADD CONSTRAINT "UQ_a870f76e685db5dbb1b28233d4f" UNIQUE ("transactionId");

CREATE INDEX "IDX_5b3e4d351605ca3b11a06d7437" ON public.apple_iap_transaction USING btree ("ownerId");

CREATE INDEX "IDX_fcead054e4577a60ac1f2908d2" ON public.apple_iap_transaction USING btree ("originalTransactionId");

CREATE INDEX "IDX_revenuecat_webhook_event_subject" ON public.revenuecat_webhook_event USING btree ("subjectKey", "eventTimestampMs");

