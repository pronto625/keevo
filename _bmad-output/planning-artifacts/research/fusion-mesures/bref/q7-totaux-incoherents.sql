-- Q7 — Factures dont le total stocké diffère du recalcul (api-bref), avant / après la mise en prod de develop (Q13).
-- Lecture seule : uniquement des SELECT. Agrégats uniquement.
--
-- Recalcul (invoice.service.ts) : total_attendu = (subtotal - discount) + tax + deliveryFee ; tolérance 1 XAF.
-- Contrôle 2 : subtotal vs SUM(invoice_item.total) ; tolérance 1 XAF ; documents sans ligne comptés à part.
-- Variable psql :develop_deploy_date (défaut 2026-10-26, date du dernier merge develop -> main, voir Q13) :
--   les documents créés avant cette date ont pu subir un PATCH de tax/discount sans recalcul (R3).
--   Trois tranches : « avant develop » (createdAt < date), « jour de la bascule » (createdAt le jour même : l heure du déploiement
--   n est pas connue, ces documents sont à classer à la main), « depuis develop » (à partir du lendemain). createdAt est un
--   TIMESTAMP sans fuseau écrit en UTC.
\if :{?develop_deploy_date}
\else
\set develop_deploy_date 2026-10-26
\endif
\echo
\echo '> Date de bascule develop utilisée (develop_deploy_date) :' :develop_deploy_date

\echo
\echo '#### Q7.a — total ≠ recalcul : par période (avant / jour de la bascule / depuis develop) × type'
SELECT CASE WHEN i."createdAt" < :'develop_deploy_date'::date THEN 'avant develop'
            WHEN i."createdAt" < :'develop_deploy_date'::date + 1 THEN 'jour de la bascule'
            ELSE 'depuis develop' END AS periode,
       i.type,
       CASE WHEN i."deletedAt" IS NULL THEN 'live' ELSE 'soft-deleted' END AS etat,
       COUNT(*) AS documents,
       COUNT(*) FILTER (WHERE abs(((i.subtotal - i.discount) + i.tax + i."deliveryFee") - i.total) > 1) AS total_incoherent,
       COALESCE(SUM(abs(((i.subtotal - i.discount) + i.tax + i."deliveryFee") - i.total))
                FILTER (WHERE abs(((i.subtotal - i.discount) + i.tax + i."deliveryFee") - i.total) > 1), 0) AS somme_ecarts_abs
FROM invoice i
GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;

\echo
\echo '#### Q7.b — total ≠ recalcul (live) : par statut × période'
SELECT i.status,
       CASE WHEN i."createdAt" < :'develop_deploy_date'::date THEN 'avant develop'
            WHEN i."createdAt" < :'develop_deploy_date'::date + 1 THEN 'jour de la bascule'
            ELSE 'depuis develop' END AS periode,
       COUNT(*) AS documents,
       COUNT(*) FILTER (WHERE abs(((i.subtotal - i.discount) + i.tax + i."deliveryFee") - i.total) > 1) AS total_incoherent
FROM invoice i WHERE i."deletedAt" IS NULL
GROUP BY 1, 2 ORDER BY 1, 2;

\echo
\echo '#### Q7.c — Taille des écarts total ≠ recalcul (live) : tranches en XAF'
SELECT CASE WHEN d <= 10 THEN '2 – 10' WHEN d <= 100 THEN '11 – 100' WHEN d <= 1000 THEN '101 – 1 000' ELSE '> 1 000' END AS ecart_xaf,
       COUNT(*) AS documents
FROM (SELECT abs(((subtotal - discount) + tax + "deliveryFee") - total) AS d FROM invoice WHERE "deletedAt" IS NULL) t
WHERE d > 1 GROUP BY 1 ORDER BY MIN(d);

\echo
\echo '#### Q7.d — subtotal ≠ somme des lignes (live) : par période'
WITH items AS (SELECT "invoiceId", SUM(total) AS somme_lignes, COUNT(*) AS n FROM invoice_item GROUP BY 1)
SELECT CASE WHEN i."createdAt" < :'develop_deploy_date'::date THEN 'avant develop'
            WHEN i."createdAt" < :'develop_deploy_date'::date + 1 THEN 'jour de la bascule'
            ELSE 'depuis develop' END AS periode,
       COUNT(*) AS documents,
       COUNT(*) FILTER (WHERE it."invoiceId" IS NULL) AS sans_ligne,
       COUNT(*) FILTER (WHERE it."invoiceId" IS NOT NULL AND abs(i.subtotal - it.somme_lignes) > 1) AS subtotal_incoherent
FROM invoice i LEFT JOIN items it ON it."invoiceId"::text = i.id::text
WHERE i."deletedAt" IS NULL
GROUP BY 1 ORDER BY 1;

\echo
\echo '#### Q7.e — Lignes dont total ≠ round(quantity × unitPrice) (tolérance 1 XAF) — live + soft-deleted confondus'
SELECT COUNT(*) AS lignes,
       COUNT(*) FILTER (WHERE abs(total - round(quantity * "unitPrice")) > 1) AS ligne_incoherente
FROM invoice_item;

\echo
\echo '#### Q7.f — Cohérence paiements (live, type sale) par statut : encaissé (initialPayment + paiements isPaid) vs total'
-- Proxy : encaissé = initialPayment + SUM(invoice_payment.amount WHERE isPaid). Un document « paid » avec encaissé < total - 1
-- est un « marquer payée » historique sans paiement daté (règle settle() de develop à reproduire à l import).
WITH paid AS (SELECT "invoiceId", SUM(amount) FILTER (WHERE "isPaid") AS encaisse FROM invoice_payment GROUP BY 1)
SELECT i.status, COUNT(*) AS documents,
       COUNT(*) FILTER (WHERE COALESCE(i."initialPayment", 0) + COALESCE(p.encaisse, 0) < i.total - 1) AS encaisse_inferieur_total,
       COUNT(*) FILTER (WHERE COALESCE(i."initialPayment", 0) + COALESCE(p.encaisse, 0) > i.total + 1) AS encaisse_superieur_total
FROM invoice i LEFT JOIN paid p ON p."invoiceId"::text = i.id::text
WHERE i."deletedAt" IS NULL AND i.type = 'sale'
GROUP BY 1 ORDER BY 1;
