-- Story 13.4 — "next release" of the test module: one more migration
CREATE TABLE provtest_labels
(
  id    UUID        NOT NULL DEFAULT gen_random_uuid(),
  label VARCHAR(50) NOT NULL,
  CONSTRAINT provtest_labels_pkey PRIMARY KEY (id)
);
