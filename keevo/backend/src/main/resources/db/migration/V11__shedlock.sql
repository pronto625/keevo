-- V11__shedlock.sql — Story 13.9 (scheduler locking)
-- Public schema only. Lock table for ShedLock (JdbcTemplateLockProvider, DB time):
-- every DB-driven @Scheduled job runs on a single instance per tick.
CREATE TABLE IF NOT EXISTS public.shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
