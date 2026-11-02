-- Q8 — Owners avec user.plan ≠ 'free' SANS abonnement en vigueur (api-bref), et écarts colonne / abonnement.
-- Lecture seule : uniquement des SELECT. Agrégats uniquement.
--
-- Règle : le plan effectif est celui de l abonnement en vigueur (SUBSCRIPTION_IN_FORCE, entitlement.service.ts) ;
-- user.plan n est qu une colonne de comparaison, remise à jour à la demande (pas de cron) : elle peut être en retard.
-- Ces owners sont ceux pour qui la décision « grâce 30 j vs FREE immédiat » (§7.10, 18.2) s applique.
\ir _definitions.psql

\echo
\echo '#### Q8.a — Matrice user.plan (colonne) × plan en vigueur (abonnement) — owners live'
-- Même choix que activePlanOf() (entitlement.service.ts) : l abonnement en vigueur le plus récemment commencé.
WITH in_force AS (
  SELECT DISTINCT ON (s."ownerId") s."ownerId", s.plan FROM subscription s
  WHERE :IN_FORCE
  ORDER BY s."ownerId", COALESCE(s."startedAt", s."createdAt") DESC
)
SELECT u.plan AS user_plan_colonne, COALESCE(f.plan, 'none') AS plan_en_vigueur,
       CASE WHEN u.plan <> 'free' AND f.plan IS NULL THEN 'Q8 : plan ≠ free sans abonnement'
            WHEN u.plan = 'free' AND f.plan IS NOT NULL THEN 'colonne en retard (payant non reflété)'
            WHEN f.plan IS NOT NULL AND u.plan <> f.plan THEN 'plan différent'
            ELSE 'cohérent' END AS diagnostic,
       COUNT(*) AS owners
FROM "user" u LEFT JOIN in_force f ON f."ownerId" = u.id
WHERE u.role = 'owner' AND u."deletedAt" IS NULL
GROUP BY 1, 2, 3 ORDER BY 1, 2;

\echo
\echo '#### Q8.b — Q8 strict : owners live, plan ≠ free, aucun abonnement en vigueur — par plan et dernier état d abonnement'
WITH in_force AS (
  SELECT DISTINCT s."ownerId" FROM subscription s
  WHERE :IN_FORCE
),
last_sub AS (
  SELECT DISTINCT ON (s."ownerId") s."ownerId", s.status, s."paymentMethod", s."endsAt"
  FROM subscription s ORDER BY s."ownerId", COALESCE(s."endsAt", s."updatedAt") DESC, s."updatedAt" DESC
)
SELECT u.plan AS user_plan_colonne,
       COALESCE(ls.status, 'aucun abonnement') AS dernier_statut_abonnement,
       COALESCE(ls."paymentMethod", '(null)') AS dernier_moyen_paiement,
       COUNT(*) AS owners
FROM "user" u
LEFT JOIN in_force f ON f."ownerId" = u.id
LEFT JOIN last_sub ls ON ls."ownerId" = u.id
WHERE u.role = 'owner' AND u."deletedAt" IS NULL AND u.plan <> 'free' AND f."ownerId" IS NULL
GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;

\echo
\echo '#### Q8.c — Q8 strict : ancienneté de l expiration (endsAt du dernier abonnement) — grâce 30 j ?'
WITH in_force AS (
  SELECT DISTINCT s."ownerId" FROM subscription s
  WHERE :IN_FORCE
),
last_sub AS (
  SELECT DISTINCT ON (s."ownerId") s."ownerId", s."endsAt"
  FROM subscription s ORDER BY s."ownerId", COALESCE(s."endsAt", s."updatedAt") DESC, s."updatedAt" DESC
)
SELECT CASE
         WHEN ls."ownerId" IS NULL THEN 'aucun abonnement'
         WHEN ls."endsAt" IS NULL THEN 'endsAt null'
         WHEN ls."endsAt" > NOW() THEN 'endsAt futur (non en vigueur pour une autre raison)'
         WHEN ls."endsAt" > NOW() - INTERVAL '30 days' THEN 'expiré < 30 j'
         WHEN ls."endsAt" > NOW() - INTERVAL '90 days' THEN 'expiré 30 – 90 j'
         ELSE 'expiré > 90 j' END AS anciennete_expiration,
       COUNT(*) AS owners
FROM "user" u
LEFT JOIN in_force f ON f."ownerId" = u.id
LEFT JOIN last_sub ls ON ls."ownerId" = u.id
WHERE u.role = 'owner' AND u."deletedAt" IS NULL AND u.plan <> 'free' AND f."ownerId" IS NULL
GROUP BY 1 ORDER BY 1;

\echo
\echo '#### Q8.d — Abonnements « actifs » en base mais hors vigueur (status = active, prédicat faux) — par cause'
SELECT CASE
         WHEN s."paymentMethod" IS NULL THEN 'paymentMethod null'
         WHEN s."startedAt" > NOW() THEN 'startedAt futur'
         WHEN s."endsAt" <= NOW() AND COALESCE(s."gracePeriodEndsAt", s."endsAt") <= NOW() THEN 'endsAt dépassé (grâce comprise)'
         ELSE 'autre' END AS cause,
       COUNT(*) AS abonnements, COUNT(DISTINCT s."ownerId") AS owners
FROM subscription s
WHERE s.status = 'active'
  AND NOT ((s."startedAt" IS NULL OR s."startedAt" <= NOW())
           AND (s."endsAt" IS NULL OR s."endsAt" > NOW() OR s."gracePeriodEndsAt" > NOW())
           AND s."paymentMethod" IS NOT NULL)
GROUP BY 1 ORDER BY 2 DESC;
