-- Q5 — Boutiques hors CM, TVA ≠ 19,25 %, montants non entiers (api-bref).
-- Lecture seule : uniquement des SELECT. Agrégats uniquement.
--
-- Règle de TVA (src/invoice/invoice.service.ts + src/invoice/constants/vat-rates.constant.ts) :
--   netHT = subtotal - discount ; tax = round(netHT × taux(pays de la boutique)) ; total = netHT + tax + deliveryFee.
--   Taux : CM 19,25 % ; SN/CI/BJ/ML/BF/TG/GN/GA 18 % ; CD/KE 16 % ; NG 7,5 % ; GH 15 % ; sinon 0.
--   skipVat (choix de l utilisateur) donne tax = 0 : signalé comme « TVA ignorée » quand le net (subtotal - discount) > 0
--   (un document entièrement remisé a légitimement tax = 0 et compte comme conforme).
-- Tolérance de comparaison : 1 XAF.

\echo
\echo '#### Q5.a — Boutiques par pays (live / soft-deleted)'
SELECT country, CASE WHEN "deletedAt" IS NULL THEN 'live' ELSE 'soft-deleted' END AS etat,
       COUNT(*) AS boutiques, COUNT(DISTINCT "userId") AS owners
FROM shop GROUP BY 1, 2 ORDER BY 1, 2;

\echo
\echo '#### Q5.b — Boutiques hors CM (live) et documents qu elles portent'
SELECT COUNT(DISTINCT sh.id) AS boutiques_hors_cm, COUNT(DISTINCT sh."userId") AS owners_hors_cm,
       COUNT(i.id) AS documents_hors_cm
FROM shop sh LEFT JOIN invoice i ON i."shopId"::text = sh.id::text AND i."deletedAt" IS NULL
WHERE sh."deletedAt" IS NULL AND upper(sh.country) <> 'CM';

\echo
\echo '#### Q5.c — Contrôle TVA par pays (documents live) : conforme / TVA ignorée / écart'
WITH inv AS (
  SELECT i.id, upper(COALESCE(sh.country, 'CM')) AS country, i.subtotal, i.discount, i.tax,
         CASE upper(COALESCE(sh.country, 'CM'))
           WHEN 'CM' THEN 0.1925
           WHEN 'SN' THEN 0.18 WHEN 'CI' THEN 0.18 WHEN 'BJ' THEN 0.18 WHEN 'ML' THEN 0.18
           WHEN 'BF' THEN 0.18 WHEN 'TG' THEN 0.18 WHEN 'GN' THEN 0.18 WHEN 'GA' THEN 0.18
           WHEN 'CD' THEN 0.16 WHEN 'KE' THEN 0.16
           WHEN 'NG' THEN 0.075
           WHEN 'GH' THEN 0.15
           ELSE 0 END AS rate
  FROM invoice i LEFT JOIN shop sh ON sh.id::text = i."shopId"::text
  WHERE i."deletedAt" IS NULL
)
SELECT country, rate AS taux_attendu,
       COUNT(*) AS documents,
       COUNT(*) FILTER (WHERE abs(tax - round((subtotal - discount) * rate)) <= 1 AND NOT (tax = 0 AND (subtotal - discount) > 0 AND rate > 0)) AS conforme,
       COUNT(*) FILTER (WHERE tax = 0 AND (subtotal - discount) > 0 AND rate > 0)                                                            AS tva_ignoree,
       COUNT(*) FILTER (WHERE abs(tax - round((subtotal - discount) * rate)) > 1 AND NOT (tax = 0 AND (subtotal - discount) > 0))             AS ecart
FROM inv GROUP BY 1, 2 ORDER BY 3 DESC, 1;

\echo
\echo '#### Q5.d — Documents (live) dont la TVA effective ≠ 19,25 % (tax > 0), par taux effectif observé'
-- Taux effectif = tax / (subtotal - discount), arrondi à 4 décimales ; seules les lignes avec net > 0 et tax > 0.
SELECT round(tax / NULLIF(subtotal - discount, 0), 4) AS taux_effectif, COUNT(*) AS documents
FROM invoice
WHERE "deletedAt" IS NULL AND tax > 0 AND (subtotal - discount) > 0
  AND abs(tax - round((subtotal - discount) * 0.1925)) > 1
GROUP BY 1 ORDER BY 2 DESC, 1
LIMIT 20;

\echo
\echo '#### Q5.e — Montants non entiers (value <> trunc(value)) par colonne — live + soft-deleted confondus'
-- Le XAF n a pas de centimes : toute décimale est une anomalie à isoler avant import (sauf invoice_item.quantity, voir Q5.f).
SELECT 'invoice.subtotal' AS colonne, COUNT(*) FILTER (WHERE subtotal <> trunc(subtotal)) AS non_entiers, COUNT(*) AS lignes FROM invoice
UNION ALL SELECT 'invoice.tax', COUNT(*) FILTER (WHERE tax <> trunc(tax)), COUNT(*) FROM invoice
UNION ALL SELECT 'invoice.discount', COUNT(*) FILTER (WHERE discount <> trunc(discount)), COUNT(*) FROM invoice
UNION ALL SELECT 'invoice.deliveryFee', COUNT(*) FILTER (WHERE "deliveryFee" <> trunc("deliveryFee")), COUNT(*) FROM invoice
UNION ALL SELECT 'invoice.total', COUNT(*) FILTER (WHERE total <> trunc(total)), COUNT(*) FROM invoice
UNION ALL SELECT 'invoice.initialPayment', COUNT(*) FILTER (WHERE "initialPayment" <> trunc("initialPayment")), COUNT(*) FROM invoice
UNION ALL SELECT 'invoice_item.unitPrice', COUNT(*) FILTER (WHERE "unitPrice" <> trunc("unitPrice")), COUNT(*) FROM invoice_item
UNION ALL SELECT 'invoice_item.total', COUNT(*) FILTER (WHERE total <> trunc(total)), COUNT(*) FROM invoice_item
UNION ALL SELECT 'invoice_payment.amount', COUNT(*) FILTER (WHERE amount <> trunc(amount)), COUNT(*) FROM invoice_payment
UNION ALL SELECT 'expense.amount', COUNT(*) FILTER (WHERE amount <> trunc(amount)), COUNT(*) FROM expense
UNION ALL SELECT 'product.unitPrice', COUNT(*) FILTER (WHERE "unitPrice" <> trunc("unitPrice")), COUNT(*) FROM product
ORDER BY 1;

\echo
\echo '#### Q5.f — Quantités décimales (invoice_item.quantity) — légitimes depuis develop (numeric(12,3)) — live + soft-deleted confondus'
SELECT COUNT(*) FILTER (WHERE quantity <> trunc(quantity)) AS lignes_quantite_decimale,
       COUNT(DISTINCT "invoiceId") FILTER (WHERE quantity <> trunc(quantity)) AS documents_concernes,
       COUNT(*) AS lignes
FROM invoice_item;

\echo
\echo '#### Q5.g — Documents (live) avec montants négatifs ou remise > sous-total (bornes)'
SELECT COUNT(*) FILTER (WHERE total < 0) AS total_negatif,
       COUNT(*) FILTER (WHERE discount > subtotal) AS remise_sup_sous_total,
       COUNT(*) FILTER (WHERE subtotal < 0 OR tax < 0 OR discount < 0 OR "deliveryFee" < 0) AS composant_negatif
FROM invoice WHERE "deletedAt" IS NULL;
