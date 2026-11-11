-- Story 24.9 (ADR-10) — SYNTHETIC load added to a restored COPY of Bref to measure the cutover's throughput (24.9, then
-- the dress rehearsal 24.12). Never run it on Bref, never on a copy that will be used for the real cutover.
--
-- No real data: every id is derived from md5('t249-…'), names are « Synthèse … », phones +2376992xxxxx / +2376993xxxxx.
-- Shape: :owners owners (default 200), 1 shop each (every 10th owner has 2), 10 products and 5 clients per shop, one
-- seller per 5th owner, 5 to 45 documents per owner (≈ 25 on average: 90 % sales paid / pending / partial, 10 % quotes,
-- 2 lines each), 3 expenses; plus ONE large owner with :big_docs documents (default 20 000) to measure the chunked
-- writes. Usage:
--   psql -d <copie> -v owners=200 -v big_docs=20000 -f bref-synthetic-load.sql
-- Remove it with: DELETE … WHERE id IN (the md5 ids) — or simply drop the copy.
\set ON_ERROR_STOP on
\if :{?owners}
\else
\set owners 200
\endif
\if :{?big_docs}
\else
\set big_docs 20000
\endif

BEGIN;

CREATE TEMP TABLE syn_owner AS
SELECT n,
       md5('t249-owner-' || n)::uuid AS id,
       '+2376992' || lpad(n::text, 5, '0') AS phone,
       CASE WHEN n = 0 THEN :big_docs ELSE 5 + (n * 7) % 41 END AS docs,
       timestamp '2025-01-05 09:00:00' + (n % 300) * interval '1 day' AS created
  FROM generate_series(0, :owners) AS n;

CREATE TEMP TABLE syn_shop AS
SELECT o.n, s.k, md5('t249-shop-' || o.n || '-' || s.k)::uuid AS id, o.id AS owner_id, o.created
  FROM syn_owner o, generate_series(1, 2) AS s(k)
 WHERE s.k = 1 OR o.n % 10 = 0;

INSERT INTO "user" (id, phone, role, "firstName", "lastName", "isActive", "isVerified", "createdAt", "updatedAt")
SELECT id, phone, 'owner', 'Synthèse', 'Propriétaire ' || n, true, true, created, created FROM syn_owner;

INSERT INTO shop (id, "userId", name, address, category, country, "createdAt", "updatedAt")
SELECT id, owner_id, 'Boutique Synthèse ' || n || '-' || k, 'Adresse synthèse', 'food', 'CM', created, created
  FROM syn_shop;

-- one seller for every 5th owner (bcrypt hash of a throw-away password)
INSERT INTO "user" (id, password, "firstName", "lastName", phone, role, "shopId", "isActive", "isVerified",
                    "createdAt", "updatedAt")
SELECT md5('t249-seller-' || s.n)::uuid, '$2b$10$abcdefghijklmnopqrstuuJ0pOaKGXUqLqRZ1xWd9ycfhzbMSqHmu', 'Synthèse',
       'Vendeur ' || s.n, '+2376993' || lpad(s.n::text, 5, '0'), 'seller', s.id::text, true, true, s.created, s.created
  FROM syn_shop s WHERE s.k = 1 AND s.n % 5 = 0;

INSERT INTO product (id, "shopId", name, "nameKey", "productKey", "unitPrice", "createdAt", "updatedAt")
SELECT md5('t249-product-' || s.n || '-' || s.k || '-' || p)::uuid, s.id::text, 'Article synthèse ' || p,
       'article synthese ' || p, 'article-synthese-' || p, 500 * p, s.created, s.created
  FROM syn_shop s, generate_series(1, 10) AS p;

INSERT INTO customer (id, "shopId", name, phone, "createdAt", "updatedAt")
SELECT md5('t249-customer-' || s.n || '-' || s.k || '-' || c)::uuid, s.id, 'Client synthèse ' || c,
       '+2376994' || lpad(((s.n * 2 + s.k) * 10 + c)::text, 5, '0'), s.created, s.created
  FROM syn_shop s, generate_series(1, 5) AS c;

CREATE TEMP TABLE syn_invoice AS
SELECT md5('t249-invoice-' || o.n || '-' || d)::uuid AS id, o.id AS owner_id,
       md5('t249-shop-' || o.n || '-' || CASE WHEN o.n % 10 = 0 AND d % 2 = 0 THEN 2 ELSE 1 END)::uuid AS shop_id,
       d, CASE WHEN d % 10 = 0 THEN 'quote' ELSE 'sale' END AS type,
       CASE WHEN d % 10 = 0 THEN 'pending' WHEN d % 7 = 0 THEN 'pending' WHEN d % 5 = 0 THEN 'partial' ELSE 'paid'
       END AS status,
       (1000 + (d * 37) % 9000) * 10 AS total,
       o.created + (d % 280) * interval '1 day' + interval '1 hour' AS created
  FROM syn_owner o, generate_series(1, (SELECT max(docs) FROM syn_owner)) AS d
 WHERE d <= o.docs;

INSERT INTO invoice (id, "userId", "shopId", "customerName", subtotal, tax, discount, "deliveryFee", total, status,
                     "paymentMethod", "initialPayment", date, type, "createdAt", "updatedAt")
SELECT id, owner_id::text, shop_id::text, 'Client synthèse', total, 0, 0, 0, total, status::invoice_status_enum,
       'cash', CASE status WHEN 'paid' THEN total WHEN 'partial' THEN total / 2 ELSE 0 END,
       to_char(created, 'YYYY-MM-DD'), type, created, created
  FROM syn_invoice;

INSERT INTO invoice_item (id, "invoiceId", reference, name, quantity, "unitPrice", total)
SELECT md5('t249-item-' || i.id || '-' || l)::uuid, i.id, 'REF-' || l, 'Article synthèse ' || l, 1,
       CASE l WHEN 1 THEN i.total - 1000 ELSE 1000 END, CASE l WHEN 1 THEN i.total - 1000 ELSE 1000 END
  FROM syn_invoice i, generate_series(1, 2) AS l;

INSERT INTO expense (id, "ownerId", "shopId", "createdById", amount, category, description, "expenseDate",
                     "paymentMethod", "createdAt", "updatedAt")
SELECT md5('t249-expense-' || s.n || '-' || e)::uuid, s.owner_id::text, s.id::text, s.owner_id::text, 2500 * e,
       'rent', 'Dépense synthèse', (s.created + e * interval '20 days')::date, 'cash', s.created, s.created
  FROM syn_shop s, generate_series(1, 3) AS e WHERE s.k = 1;

COMMIT;

SELECT (SELECT count(*) FROM syn_owner) AS owners, (SELECT count(*) FROM syn_shop) AS shops,
       (SELECT count(*) FROM syn_invoice) AS documents, (SELECT count(*) * 2 FROM syn_invoice) AS lines;
