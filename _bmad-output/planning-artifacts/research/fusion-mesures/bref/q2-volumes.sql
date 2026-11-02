-- Q2 — Volumes par table (api-bref) et répartition par owner (percentiles) pour dimensionner les vagues.
-- Lecture seule : uniquement des SELECT. Les lignes soft-deleted ("deletedAt") sont comptées à part, jamais exclues en silence.
-- Schéma : refresh_token n'existe que sur develop ; son comptage exact est gardé par \if (le catalogue Q2.a le liste de toute façon).
SELECT to_regclass('public.refresh_token') IS NOT NULL AS has_refresh_token \gset

\echo
\echo '#### Q2.a — Toutes les tables de public : estimation catalogue (pg_stat_user_tables.n_live_tup, taille totale)'
-- Estimation (statistiques du planificateur), indépendante de la liste des tables connues du kit : révèle aussi les tables inattendues.
SELECT relname AS "table", n_live_tup AS lignes_estimees, n_dead_tup AS mortes_estimees,
       pg_size_pretty(pg_total_relation_size(relid)) AS taille_totale
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY relname;

\echo
\echo '#### Q2.b — Comptages exacts par table (live / soft-deleted quand la table a "deletedAt")'
SELECT 'user'                           AS "table", 'live' AS etat, COUNT(*) AS lignes FROM "user" WHERE "deletedAt" IS NULL
UNION ALL SELECT 'user', 'soft-deleted', COUNT(*) FROM "user" WHERE "deletedAt" IS NOT NULL
UNION ALL SELECT 'shop', 'live', COUNT(*) FROM shop WHERE "deletedAt" IS NULL
UNION ALL SELECT 'shop', 'soft-deleted', COUNT(*) FROM shop WHERE "deletedAt" IS NOT NULL
UNION ALL SELECT 'invoice', 'live', COUNT(*) FROM invoice WHERE "deletedAt" IS NULL
UNION ALL SELECT 'invoice', 'soft-deleted', COUNT(*) FROM invoice WHERE "deletedAt" IS NOT NULL
UNION ALL SELECT 'invoice_item', 'all', COUNT(*) FROM invoice_item
UNION ALL SELECT 'invoice_payment', 'all', COUNT(*) FROM invoice_payment
UNION ALL SELECT 'customer', 'all', COUNT(*) FROM customer
UNION ALL SELECT 'client (comptes portail)', 'all', COUNT(*) FROM client
UNION ALL SELECT 'product', 'all', COUNT(*) FROM product
UNION ALL SELECT 'product (isArchived)', 'all', COUNT(*) FROM product WHERE "isArchived"
UNION ALL SELECT 'product (mergedIntoId not null)', 'all', COUNT(*) FROM product WHERE "mergedIntoId" IS NOT NULL
UNION ALL SELECT 'expense', 'live', COUNT(*) FROM expense WHERE "deletedAt" IS NULL
UNION ALL SELECT 'expense', 'soft-deleted', COUNT(*) FROM expense WHERE "deletedAt" IS NOT NULL
UNION ALL SELECT 'subscription', 'all', COUNT(*) FROM subscription
UNION ALL SELECT 'account_credit', 'all', COUNT(*) FROM account_credit
UNION ALL SELECT 'usage_counter', 'all', COUNT(*) FROM usage_counter
UNION ALL SELECT 'plan_payment_request', 'all', COUNT(*) FROM plan_payment_request
UNION ALL SELECT 'sms_recharge_request', 'all', COUNT(*) FROM sms_recharge_request
UNION ALL SELECT 'push_token', 'all', COUNT(*) FROM push_token
UNION ALL SELECT 'apple_iap_transaction', 'all', COUNT(*) FROM apple_iap_transaction
UNION ALL SELECT 'apple_iap_verification_request', 'all', COUNT(*) FROM apple_iap_verification_request
UNION ALL SELECT 'whatsapp_account', 'all', COUNT(*) FROM whatsapp_account
UNION ALL SELECT 'whatsapp_message', 'all', COUNT(*) FROM whatsapp_message
UNION ALL SELECT 'whatsapp_chat_message', 'all', COUNT(*) FROM whatsapp_chat_message
UNION ALL SELECT 'customer_campaign', 'all', COUNT(*) FROM customer_campaign
UNION ALL SELECT 'owner_whatsapp_template', 'all', COUNT(*) FROM owner_whatsapp_template
UNION ALL SELECT 'campaign', 'all', COUNT(*) FROM campaign
UNION ALL SELECT 'invoice_outbox', 'all', COUNT(*) FROM invoice_outbox
UNION ALL SELECT 'notification', 'all', COUNT(*) FROM notification
UNION ALL SELECT 'ai_conversation', 'all', COUNT(*) FROM ai_conversation
UNION ALL SELECT 'web_push_subscription', 'all', COUNT(*) FROM web_push_subscription
UNION ALL SELECT 'otp', 'all', COUNT(*) FROM otp
UNION ALL SELECT 'app_version', 'all', COUNT(*) FROM app_version
ORDER BY 1, 2;

\echo
\echo '#### Q2.b-bis — refresh_token (table develop seulement)'
\if :has_refresh_token
SELECT 'refresh_token' AS "table", 'all' AS etat, COUNT(*) AS lignes,
       COUNT(*) FILTER (WHERE "revokedAt" IS NULL AND "replacedAt" IS NULL AND "expiresAt" > NOW()) AS valides
FROM refresh_token;
\else
\echo 'refresh_token : non mesuré (objet absent : schéma main)'
\endif

\echo
\echo '#### Q2.c — Utilisateurs par rôle (live)'
SELECT role, COUNT(*) AS utilisateurs FROM "user" WHERE "deletedAt" IS NULL GROUP BY 1 ORDER BY 1;

\echo
\echo '#### Q2.d — Documents (invoice live) par type × statut'
SELECT COALESCE(type, 'TOTAL') AS type, COALESCE(status::text, 'tous statuts') AS status, COUNT(*) AS documents, SUM(total) AS somme_total
FROM invoice WHERE "deletedAt" IS NULL
GROUP BY ROLLUP (type, status)
ORDER BY type NULLS LAST, status NULLS LAST;

\echo
\echo '#### Q2.e — Documents par année de création (invoice live)'
SELECT EXTRACT(YEAR FROM "createdAt")::int AS annee, COUNT(*) AS documents
FROM invoice WHERE "deletedAt" IS NULL GROUP BY 1 ORDER BY 1;

\echo
\echo '#### Q2.f — Répartition par owner (percentiles sur les owners live) : taille des vagues'
-- Chaque owner live compte, y compris avec 0 ligne (LEFT JOIN). Membres = "user" rattachés aux boutiques de l owner.
WITH owners AS (SELECT id FROM "user" WHERE role = 'owner' AND "deletedAt" IS NULL),
per_owner AS (
  SELECT o.id,
    (SELECT COUNT(*) FROM shop sh WHERE sh."userId"::text = o.id::text AND sh."deletedAt" IS NULL)            AS shops,
    (SELECT COUNT(*) FROM "user" m JOIN shop sh ON m."shopId"::text = sh.id::text
       WHERE sh."userId"::text = o.id::text AND m."deletedAt" IS NULL)                                        AS membres,
    (SELECT COUNT(*) FROM invoice i JOIN shop sh ON i."shopId"::text = sh.id::text
       WHERE sh."userId"::text = o.id::text AND i."deletedAt" IS NULL)                                        AS documents,
    (SELECT COUNT(*) FROM invoice_item ii JOIN invoice i ON ii."invoiceId"::text = i.id::text
       JOIN shop sh ON i."shopId"::text = sh.id::text WHERE sh."userId"::text = o.id::text)                   AS lignes,
    (SELECT COUNT(*) FROM invoice_payment ip JOIN invoice i ON ip."invoiceId"::text = i.id::text
       JOIN shop sh ON i."shopId"::text = sh.id::text WHERE sh."userId"::text = o.id::text)                   AS paiements,
    (SELECT COUNT(*) FROM customer c JOIN shop sh ON c."shopId"::text = sh.id::text
       WHERE sh."userId"::text = o.id::text)                                                                  AS clients,
    (SELECT COUNT(*) FROM product p JOIN shop sh ON p."shopId"::text = sh.id::text
       WHERE sh."userId"::text = o.id::text)                                                                  AS produits,
    (SELECT COUNT(*) FROM expense e WHERE e."ownerId"::text = o.id::text AND e."deletedAt" IS NULL)           AS depenses
  FROM owners o
)
SELECT m.mesure,
       SUM(v) AS total, MIN(v) AS min,
       ROUND(percentile_cont(0.5)  WITHIN GROUP (ORDER BY v)::numeric, 1) AS p50,
       ROUND(percentile_cont(0.9)  WITHIN GROUP (ORDER BY v)::numeric, 1) AS p90,
       ROUND(percentile_cont(0.99) WITHIN GROUP (ORDER BY v)::numeric, 1) AS p99,
       MAX(v) AS max,
       COUNT(*) FILTER (WHERE v = 0) AS owners_a_zero
FROM per_owner
CROSS JOIN LATERAL (VALUES
  ('shops', shops), ('membres', membres), ('documents', documents), ('lignes', lignes),
  ('paiements', paiements), ('clients', clients), ('produits', produits), ('depenses', depenses)) AS m(mesure, v)
GROUP BY m.mesure
ORDER BY m.mesure;

\echo
\echo '#### Q2.g — Owners par tranche de documents (ordre des vagues)'
WITH owners AS (SELECT id FROM "user" WHERE role = 'owner' AND "deletedAt" IS NULL),
per_owner AS (
  SELECT o.id, (SELECT COUNT(*) FROM invoice i JOIN shop sh ON i."shopId"::text = sh.id::text
                WHERE sh."userId"::text = o.id::text AND i."deletedAt" IS NULL) AS documents
  FROM owners o)
SELECT CASE
         WHEN documents = 0 THEN '0'
         WHEN documents < 10 THEN '1-9'
         WHEN documents < 100 THEN '10-99'
         WHEN documents < 1000 THEN '100-999'
         ELSE '1000+' END AS tranche_documents,
       COUNT(*) AS owners, SUM(documents) AS documents
FROM per_owner GROUP BY 1 ORDER BY MIN(documents);

\echo
\echo '#### Q2.h — Taille de la base'
SELECT pg_size_pretty(pg_database_size(current_database())) AS taille_base;
