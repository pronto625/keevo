-- Q1 — Owners Bref : total, « actifs < 90 j » (proxies), payants par plan et moyen de paiement.
-- Base api-bref (schéma unique, tables TypeORM singulières, identifiants camelCase entre guillemets).
-- Lecture seule : uniquement des SELECT. Agrégats uniquement — aucune valeur individuelle.
--
-- Définitions :
--   owner        = "user" WHERE role = 'owner' ; les lignes soft-deleted ("deletedAt" NOT NULL) sont comptées à part.
--   membre       = "user" WHERE role IN ('admin','seller','viewer') ; rattaché à l'owner via "user"."shopId" -> shop.id -> shop."userId".
--   en vigueur   = :IN_FORCE (bref/_definitions.psql = SUBSCRIPTION_IN_FORCE de entitlement.service.ts), évalué à NOW().
--   buckets moyen de paiement : apple | admin_grant | mycoolpay = {orange_money, momo, card, wave}.
-- Schéma : la table refresh_token n'existe que sur develop (migration 1753650000000) ; sur un réplica de main (prod)
--   le PROXY 1 est marqué « non mesuré » et les autres proxies sont tout de même calculés.
\ir _definitions.psql
SELECT to_regclass('public.refresh_token') IS NOT NULL AS has_refresh_token \gset

\echo
\echo '#### Q1.a — Owners : total, actifs/inactifs, supprimés (soft delete)'
-- Définition : role = 'owner'. "isActive" est le flag de compte (pas une activité récente).
SELECT
  CASE WHEN "deletedAt" IS NULL THEN 'live' ELSE 'soft-deleted' END AS etat,
  "isActive"                                                        AS compte_actif,
  COUNT(*)                                                          AS owners
FROM "user"
WHERE role = 'owner'
GROUP BY 1, 2
ORDER BY 1, 2;

\echo
\echo '#### Q1.b — Owners « actifs < 90 jours » : trois PROXIES (aucune colonne de dernière connexion)'
-- PROXY 1 : un refresh_token."createdAt" >= now()-90 j pour l owner ou l un de ses membres (reconnexion ou rotation) — develop seulement.
-- PROXY 2 : une invoice."createdAt" >= now()-90 j dans une boutique de l owner (activité métier).
-- PROXY 3 : un push_token."updatedAt" >= now()-90 j pour l owner ou l un de ses membres (appareil vu).
-- « au moins un proxy » = union des proxies disponibles. Les owners soft-deleted sont exclus de ce calcul.
\if :has_refresh_token
WITH owners AS (
  SELECT id FROM "user" WHERE role = 'owner' AND "deletedAt" IS NULL
),
owner_users AS (                       -- owner + ses membres
  SELECT o.id AS owner_id, o.id::text AS user_id FROM owners o
  UNION
  SELECT o.id, m.id::text
  FROM owners o
  JOIN shop sh ON sh."userId"::text = o.id::text
  JOIN "user" m ON m."shopId"::text = sh.id::text AND m."deletedAt" IS NULL
),
p1 AS (
  SELECT DISTINCT ou.owner_id FROM owner_users ou
  JOIN refresh_token rt ON rt."userId"::text = ou.user_id
  WHERE rt."createdAt" >= NOW() - INTERVAL '90 days'
),
p2 AS (
  SELECT DISTINCT o.id AS owner_id FROM owners o
  JOIN shop sh ON sh."userId"::text = o.id::text
  JOIN invoice i ON i."shopId"::text = sh.id::text
  WHERE i."createdAt" >= NOW() - INTERVAL '90 days'
),
p3 AS (
  SELECT DISTINCT ou.owner_id FROM owner_users ou
  JOIN push_token pt ON pt."userId"::text = ou.user_id
  WHERE pt."updatedAt" >= NOW() - INTERVAL '90 days'
)
SELECT 'owners live (base)'                                   AS mesure, COUNT(*) AS owners FROM owners
UNION ALL SELECT 'PROXY 1 : refresh_token < 90 j (owner ou membre)', COUNT(*) FROM p1
UNION ALL SELECT 'PROXY 2 : invoice créée < 90 j dans ses boutiques', COUNT(*) FROM p2
UNION ALL SELECT 'PROXY 3 : push_token mis à jour < 90 j (owner ou membre)', COUNT(*) FROM p3
UNION ALL SELECT 'au moins un proxy (union 1+2+3)', COUNT(*) FROM (
  SELECT owner_id FROM p1 UNION SELECT owner_id FROM p2 UNION SELECT owner_id FROM p3) u;
\else
\echo 'PROXY 1 (refresh_token) : non mesuré (objet absent : schéma main — la table refresh_token n existe que sur develop)'
WITH owners AS (
  SELECT id FROM "user" WHERE role = 'owner' AND "deletedAt" IS NULL
),
owner_users AS (
  SELECT o.id AS owner_id, o.id::text AS user_id FROM owners o
  UNION
  SELECT o.id, m.id::text
  FROM owners o
  JOIN shop sh ON sh."userId"::text = o.id::text
  JOIN "user" m ON m."shopId"::text = sh.id::text AND m."deletedAt" IS NULL
),
p2 AS (
  SELECT DISTINCT o.id AS owner_id FROM owners o
  JOIN shop sh ON sh."userId"::text = o.id::text
  JOIN invoice i ON i."shopId"::text = sh.id::text
  WHERE i."createdAt" >= NOW() - INTERVAL '90 days'
),
p3 AS (
  SELECT DISTINCT ou.owner_id FROM owner_users ou
  JOIN push_token pt ON pt."userId"::text = ou.user_id
  WHERE pt."updatedAt" >= NOW() - INTERVAL '90 days'
)
SELECT 'owners live (base)'                                   AS mesure, COUNT(*) AS owners FROM owners
UNION ALL SELECT 'PROXY 2 : invoice créée < 90 j dans ses boutiques', COUNT(*) FROM p2
UNION ALL SELECT 'PROXY 3 : push_token mis à jour < 90 j (owner ou membre)', COUNT(*) FROM p3
UNION ALL SELECT 'au moins un proxy (union 2+3)', COUNT(*) FROM (
  SELECT owner_id FROM p2 UNION SELECT owner_id FROM p3) u;
\endif

\echo
\echo '#### Q1.c — Owners payants (abonnement EN VIGUEUR) par plan × moyen de paiement'
-- Prédicat :IN_FORCE (alias s). Un owner est compté une fois par couple plan × bucket.
WITH in_force AS (
  SELECT DISTINCT s."ownerId", s.plan,
         CASE s."paymentMethod"
           WHEN 'apple' THEN 'apple'
           WHEN 'admin_grant' THEN 'admin_grant'
           WHEN 'orange_money' THEN 'mycoolpay' WHEN 'momo' THEN 'mycoolpay'
           WHEN 'card' THEN 'mycoolpay' WHEN 'wave' THEN 'mycoolpay'
           ELSE 'autre:' || COALESCE(s."paymentMethod", 'null')
         END AS bucket
  FROM subscription s
  JOIN "user" u ON u.id = s."ownerId" AND u.role = 'owner' AND u."deletedAt" IS NULL
  WHERE :IN_FORCE
)
SELECT COALESCE(plan, 'TOTAL') AS plan, COALESCE(bucket, 'tous moyens') AS moyen_paiement, COUNT(DISTINCT "ownerId") AS owners
FROM in_force
GROUP BY ROLLUP (plan, bucket)
ORDER BY plan NULLS LAST, bucket NULLS LAST;

\echo
\echo '#### Q1.d — Owners payants : total distinct et owners ayant > 1 abonnement en vigueur (anomalie)'
WITH in_force AS (
  SELECT s."ownerId", COUNT(*) AS n
  FROM subscription s
  JOIN "user" u ON u.id = s."ownerId" AND u.role = 'owner' AND u."deletedAt" IS NULL
  WHERE :IN_FORCE
  GROUP BY s."ownerId"
)
SELECT 'owners payants distincts' AS mesure, COUNT(*) AS valeur FROM in_force
UNION ALL SELECT 'owners avec > 1 abonnement en vigueur', COUNT(*) FROM in_force WHERE n > 1;

\echo
\echo '#### Q1.e — Toutes les lignes subscription par statut × moyen de paiement (historique inclus)'
SELECT status, COALESCE("paymentMethod", '(null)') AS moyen_paiement, COUNT(*) AS lignes,
       COUNT(DISTINCT "ownerId") AS owners, SUM("amountPaid") AS somme_amount_paid
FROM subscription
GROUP BY 1, 2
ORDER BY 1, 2;

\echo
\echo '#### Q1.f — Abonnements en grâce Apple (endsAt dépassé, gracePeriodEndsAt > now)'
SELECT COUNT(*) AS abonnements_en_grace, COUNT(DISTINCT "ownerId") AS owners
FROM subscription s
WHERE s.status = 'active' AND s."paymentMethod" IS NOT NULL
  AND s."endsAt" IS NOT NULL AND s."endsAt" <= NOW() AND s."gracePeriodEndsAt" > NOW();

\echo
\echo '#### Q1.g — Demandes de paiement MyCoolPay (plan_payment_request) par statut × moyen'
SELECT status, COALESCE("paymentMethod", '(null)') AS moyen_paiement, COUNT(*) AS demandes,
       SUM(amount) AS somme_amount, SUM("creditApplied") AS somme_credit_applique, SUM("amountDue") AS somme_due
FROM plan_payment_request
GROUP BY 1, 2
ORDER BY 1, 2;
