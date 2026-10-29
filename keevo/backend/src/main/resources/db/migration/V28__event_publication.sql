-- V28__event_publication.sql — Story 12.4 (durable cross-module events)
-- Public schema only (global registry; tenant isolation comes from the tenant carried by each event).
--
-- 1. Spring Modulith 1.4 JDBC Event Publication Registry (transactional outbox). Exact DDL of
--    spring-modulith-events-jdbc 1.4.14 schema-postgresql.sql, schema-qualified: the tenant
--    connection provider switches search_path per tenant, so the table must never be resolved
--    unqualified (spring.modulith.events.jdbc.schema=public, schema-initialization disabled).
CREATE TABLE IF NOT EXISTS public.event_publication
(
  id               UUID NOT NULL,
  listener_id      TEXT NOT NULL,
  event_type       TEXT NOT NULL,
  serialized_event TEXT NOT NULL,
  publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date  TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON public.event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON public.event_publication (completion_date);

-- 2. Delivery receipts of the @DeliverOnce listeners (push / WhatsApp senders): one row per
--    (listener, event) taken before the message is sent, so a republished publication (restart,
--    resubmission) never sends the same message twice. event_hash = SHA-256 of
--    "<event class name>:<event JSON written by the application ObjectMapper>" (the mapper the
--    registry serializes with). Purged after 30 days (EventPublicationPurgeJob).
CREATE TABLE IF NOT EXISTS public.event_listener_receipt
(
  listener_id TEXT        NOT NULL,
  event_hash  CHAR(64)    NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (listener_id, event_hash)
);
CREATE INDEX IF NOT EXISTS event_listener_receipt_received_at_idx ON public.event_listener_receipt (received_at);
