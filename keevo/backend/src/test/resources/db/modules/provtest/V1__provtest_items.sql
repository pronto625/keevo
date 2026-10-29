-- Story 13.4 — test-only module migration (unqualified: Flyway's defaultSchema is the tenant schema)
CREATE TABLE provtest_items
(
  id   UUID         NOT NULL DEFAULT gen_random_uuid(),
  name VARCHAR(100) NOT NULL,
  CONSTRAINT provtest_items_pkey PRIMARY KEY (id)
);
