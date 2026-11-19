-- Notes and requested issue date of a document, entered with its content (the app's invoice form).
-- Additive only: both nullable, no default, never filtered. `notes` is free text kept with the document;
-- `requested_issue_date` is the day the seller wants the invoice dated, honoured at issue (null = the day of the
-- issue). Idempotent (module script, README rules).
SET LOCAL lock_timeout = '10s';

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS notes TEXT;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS requested_issue_date DATE;
