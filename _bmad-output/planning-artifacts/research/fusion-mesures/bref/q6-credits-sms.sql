-- Q6 — Crédits de compte restants et packs SMS (api-bref) : engagements commerciaux à reporter à l identique (R9).
-- Lecture seule : uniquement des SELECT. Agrégats uniquement.
--
-- Période courante : api-bref importe src/utils/force-utc.ts (process.env.TZ = 'UTC') avant toute Date, donc
--   currentPeriod() = 'YYYY-MM' en UTC ; Q6.c calcule la période courante en UTC, comme le code.
-- Sources et proxies :
--   account_credit(ownerId unique, amount)  : solde d argent (XAF) par owner.
--   usage_counter(ownerId, metric, periodMonth, used) : compteurs mensuels ('YYYY-MM') pour invoices / ai_credits / sms / email,
--     et compteur à vie periodMonth = 'all' pour 'sms_free' (limite gratuite 5 SMS, quota.service.ts).
--   sms_recharge_request(smsQuantity, amountDue, status, completedAt) : demandes d achat de packs SMS.
--     Aucune colonne de solde SMS n existe dans le code (vérifié : seule la création de la demande est implémentée,
--     aucun traitement de complétion n alimente un compteur). On rapporte donc :
--       « achetés (complétés) » = SUM(smsQuantity) WHERE status = 'completed'  (ce que le client a payé)
--       « consommés » (PROXY)   = SUM(usage_counter.used) WHERE metric = 'sms' (envois mensuels, toutes périodes)
--     Le « restant » n est PAS calculable de façon fiable : à décider en 18.2 (reporter le pack acheté tel quel).
\ir _definitions.psql

\echo
\echo '#### Q6.a — Crédits de compte (account_credit)'
SELECT COUNT(*)                                   AS owners_avec_ligne,
       COUNT(*) FILTER (WHERE amount > 0)         AS owners_credit_positif,
       COUNT(*) FILTER (WHERE amount < 0)         AS owners_credit_negatif,
       COALESCE(SUM(amount) FILTER (WHERE amount > 0), 0) AS somme_credits_positifs,
       MAX(amount)                                AS credit_max,
       ROUND(percentile_cont(0.5) WITHIN GROUP (ORDER BY amount) FILTER (WHERE amount > 0)::numeric, 0) AS p50_credit_positif
FROM account_credit;

\echo
\echo '#### Q6.b — Crédits positifs : par tranche et par état du plan de l owner'
SELECT CASE WHEN ac.amount < 1000 THEN '< 1 000' WHEN ac.amount < 5000 THEN '1 000 – 4 999'
            WHEN ac.amount < 20000 THEN '5 000 – 19 999' ELSE '≥ 20 000' END AS tranche_xaf,
       COUNT(*) AS owners, SUM(ac.amount) AS somme
FROM account_credit ac
JOIN "user" u ON u.id = ac."ownerId"
WHERE ac.amount > 0 AND u."deletedAt" IS NULL
GROUP BY 1 ORDER BY MIN(ac.amount);

\echo
\echo '#### Q6.c — Compteurs d usage (usage_counter) par métrique : période courante et cumul'
SELECT metric,
       COUNT(*) FILTER (WHERE "periodMonth" = to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM')) AS owners_periode_courante,
       COALESCE(SUM(used) FILTER (WHERE "periodMonth" = to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM')), 0) AS used_periode_courante,
       COUNT(*) FILTER (WHERE "periodMonth" = 'all') AS owners_compteur_a_vie,
       COALESCE(SUM(used) FILTER (WHERE "periodMonth" = 'all'), 0) AS used_a_vie,
       COALESCE(SUM(used) FILTER (WHERE "periodMonth" <> 'all'), 0) AS used_toutes_periodes_mensuelles,
       COUNT(DISTINCT "periodMonth") AS periodes_distinctes
FROM usage_counter GROUP BY 1 ORDER BY 1;

\echo
\echo '#### Q6.d — SMS gratuits (sms_free, à vie, limite 5) : owners par nombre utilisé'
SELECT used AS sms_free_utilises, COUNT(*) AS owners
FROM usage_counter WHERE metric = 'sms_free' AND "periodMonth" = 'all'
GROUP BY 1 ORDER BY 1;

\echo
\echo '#### Q6.e — Packs SMS (sms_recharge_request) par statut : demandes, SMS, montants'
SELECT COALESCE(status, 'TOTAL') AS status, COALESCE("paymentMethod", 'tous moyens') AS moyen_paiement, COUNT(*) AS demandes, COUNT(DISTINCT "userId") AS owners,
       SUM("smsQuantity") AS sms_total, SUM("amountDue") AS somme_due_xaf,
       COUNT(*) FILTER (WHERE "completedAt" IS NOT NULL) AS avec_completed_at
FROM sms_recharge_request
GROUP BY ROLLUP (status, "paymentMethod")
ORDER BY status NULLS LAST, "paymentMethod" NULLS LAST;

\echo
\echo '#### Q6.f — Bilan SMS : achetés (complétés) vs consommés (PROXY usage_counter.sms)'
SELECT
  (SELECT COALESCE(SUM("smsQuantity"), 0) FROM sms_recharge_request WHERE status = 'completed') AS sms_achetes_completes,
  (SELECT COUNT(DISTINCT "userId") FROM sms_recharge_request WHERE status = 'completed')       AS owners_ayant_achete,
  (SELECT COALESCE(SUM(used), 0) FROM usage_counter WHERE metric = 'sms' AND "periodMonth" <> 'all') AS sms_consommes_proxy,
  (SELECT COALESCE(SUM(used), 0) FROM usage_counter WHERE metric = 'sms_free' AND "periodMonth" = 'all') AS sms_gratuits_consommes;

\echo
\echo '#### Q6.g — Owners live ayant un pack complété : par plan en vigueur (none = pas d abonnement en vigueur)'
WITH in_force AS (
  SELECT DISTINCT s."ownerId", s.plan FROM subscription s
  JOIN "user" u ON u.id = s."ownerId" AND u."deletedAt" IS NULL
  WHERE :IN_FORCE
)
SELECT COALESCE(f.plan, 'none') AS plan_en_vigueur, COUNT(DISTINCT r."userId") AS owners, SUM(r."smsQuantity") AS sms_achetes
FROM sms_recharge_request r
JOIN "user" u ON u.id::text = r."userId"::text AND u."deletedAt" IS NULL
LEFT JOIN in_force f ON f."ownerId"::text = r."userId"::text
WHERE r.status = 'completed'
GROUP BY 1 ORDER BY 1;
