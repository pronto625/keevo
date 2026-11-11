-- Story 24.6 — the Bref annex tables the import reads (whatsapp_account, owner_whatsapp_template, customer_campaign,
-- whatsapp_message, whatsapp_chat_message, ai_conversation, notification, web_push_subscription), as api-bref creates them: DDL only, from a
-- pg_dump of the 03/11 Bref database (no row; keys and indexes kept). Applied after bref-source-schema.sql. Rows come
-- from the versioned synthetic fixture migration/bref-annexes-fixture.sql and the tests (random ids), never real data.

CREATE TABLE public.ai_conversation (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "userId" character varying NOT NULL,
    title character varying DEFAULT ''::character varying NOT NULL,
    "messageCount" integer DEFAULT 0 NOT NULL,
    messages jsonb DEFAULT '[]'::jsonb NOT NULL,
    "aiHistory" jsonb DEFAULT '[]'::jsonb NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.customer_campaign (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "ownerId" uuid NOT NULL,
    name character varying NOT NULL,
    description character varying,
    status character varying DEFAULT 'draft'::character varying NOT NULL,
    "targetingCriteria" jsonb DEFAULT '{}'::jsonb NOT NULL,
    whatsapp jsonb,
    "targetedCount" integer DEFAULT 0 NOT NULL,
    "sentCount" integer DEFAULT 0 NOT NULL,
    "deliveredCount" integer DEFAULT 0 NOT NULL,
    "readCount" integer DEFAULT 0 NOT NULL,
    "failedCount" integer DEFAULT 0 NOT NULL,
    "scheduledAt" timestamp without time zone,
    "sentAt" timestamp without time zone,
    "createdById" uuid NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.notification (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "userId" character varying NOT NULL,
    type character varying NOT NULL,
    channel character varying DEFAULT 'push'::character varying NOT NULL,
    title character varying NOT NULL,
    message character varying NOT NULL,
    "isRead" boolean DEFAULT false NOT NULL,
    metadata jsonb,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.owner_whatsapp_template (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "ownerId" uuid NOT NULL,
    name character varying NOT NULL,
    category character varying NOT NULL,
    language character varying NOT NULL,
    "headerFormat" character varying DEFAULT 'NONE'::character varying NOT NULL,
    "headerText" character varying,
    "headerMediaUrl" character varying,
    "bodyText" character varying NOT NULL,
    "bodyExamples" jsonb DEFAULT '[]'::jsonb NOT NULL,
    "footerText" character varying,
    status character varying DEFAULT 'DRAFT'::character varying NOT NULL,
    "metaTemplateId" character varying,
    "rejectedReason" character varying,
    "submitError" character varying,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.whatsapp_account (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "ownerId" uuid NOT NULL,
    "wabaId" character varying NOT NULL,
    "phoneNumberId" character varying NOT NULL,
    "displayPhoneNumber" character varying,
    "verifiedName" character varying,
    "accessToken" character varying NOT NULL,
    status character varying DEFAULT 'connected'::character varying NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.whatsapp_chat_message (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "ownerId" uuid,
    "contactPhone" character varying NOT NULL,
    "contactName" character varying,
    direction character varying NOT NULL,
    type character varying DEFAULT 'text'::character varying NOT NULL,
    body text,
    "waMessageId" character varying,
    status character varying,
    "readAt" timestamp without time zone,
    "sentById" uuid,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.whatsapp_message (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "campaignId" uuid,
    "userId" uuid,
    phone character varying NOT NULL,
    "templateName" character varying NOT NULL,
    "waMessageId" character varying,
    status character varying DEFAULT 'pending'::character varying NOT NULL,
    error character varying,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "customerCampaignId" uuid,
    "customerId" uuid
);

CREATE TABLE public.web_push_subscription (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "userId" character varying NOT NULL,
    endpoint character varying NOT NULL,
    p256dh character varying NOT NULL,
    auth character varying NOT NULL,
    "userAgent" character varying,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.web_push_subscription ADD CONSTRAINT "PK_08337e9e8b3ca6f53c3d9591116" PRIMARY KEY (id);
ALTER TABLE ONLY public.web_push_subscription ADD CONSTRAINT "UQ_7103f32a09826244a9378ac68dd" UNIQUE (endpoint);
CREATE INDEX "IDX_3c2a56e7bcf4ad3f92d872b81c" ON public.web_push_subscription USING btree ("userId");
ALTER TABLE ONLY public.whatsapp_chat_message ADD CONSTRAINT "PK_1cc146a985be18607d97664b70a" PRIMARY KEY (id);
ALTER TABLE ONLY public.owner_whatsapp_template ADD CONSTRAINT "PK_577880eaffd34e9498364ec677e" PRIMARY KEY (id);
ALTER TABLE ONLY public.notification ADD CONSTRAINT "PK_705b6c7cdf9b2c2ff7ac7872cb7" PRIMARY KEY (id);
ALTER TABLE ONLY public.whatsapp_account ADD CONSTRAINT "PK_9251808fe3ec13a79020f557297" PRIMARY KEY (id);
ALTER TABLE ONLY public.ai_conversation ADD CONSTRAINT "PK_c5c36eee51de52a151fb73f7a51" PRIMARY KEY (id);
ALTER TABLE ONLY public.whatsapp_message ADD CONSTRAINT "PK_d7b16d171c675c93098598a54d8" PRIMARY KEY (id);
ALTER TABLE ONLY public.customer_campaign ADD CONSTRAINT "PK_eab592ebe4d5293c0936c2f505c" PRIMARY KEY (id);
ALTER TABLE ONLY public.whatsapp_account ADD CONSTRAINT "UQ_ad80d8f05a80ab004b1f21b0bd7" UNIQUE ("ownerId");
ALTER TABLE ONLY public.owner_whatsapp_template
    ADD CONSTRAINT "UQ_owner_whatsapp_template_name" UNIQUE ("ownerId", name, language);
CREATE INDEX "IDX_039c7fcba309434f48b3ffaa58" ON public.notification USING btree ("userId", "createdAt");
CREATE INDEX "IDX_1ced25315eb974b73391fb1c81" ON public.notification USING btree ("userId");
CREATE INDEX "IDX_customer_campaign_owner" ON public.customer_campaign USING btree ("ownerId");
CREATE INDEX "IDX_whatsapp_chat_thread" ON public.whatsapp_chat_message USING btree ("ownerId", "contactPhone", "createdAt");
CREATE INDEX "IDX_whatsapp_chat_wa_id" ON public.whatsapp_chat_message USING btree ("waMessageId");
CREATE INDEX "IDX_whatsapp_message_campaign" ON public.whatsapp_message USING btree ("campaignId");
CREATE INDEX "IDX_whatsapp_message_customer_campaign" ON public.whatsapp_message USING btree ("customerCampaignId");
CREATE INDEX "IDX_whatsapp_message_wa_id" ON public.whatsapp_message USING btree ("waMessageId");
