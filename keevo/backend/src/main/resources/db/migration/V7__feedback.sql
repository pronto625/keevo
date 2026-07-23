CREATE TABLE IF NOT EXISTS public.feedback (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    type            varchar(30)  NOT NULL,
    description     varchar(500) NOT NULL,
    tenant_id       varchar(15)  NOT NULL,
    user_id         uuid         NOT NULL,
    app_version     varchar(20),
    platform        varchar(20),
    screen_context  varchar(200),
    submitted_at    timestamp(6) with time zone NOT NULL,
    priority        varchar(10)  NOT NULL DEFAULT 'NORMAL',
    created_at      timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp(6) with time zone NOT NULL DEFAULT now()
);
