-- Story 13.4 — deliberately invalid: the migration must fail and roll back
ALTER TABLE provbroken_items ADD COLUMN ok_column INT;
CREATE TABLEX provbroken_oops (id INT);
