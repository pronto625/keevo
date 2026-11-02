-- Q3 — Identités Bref sans téléphone (owners email-only / Google / Apple ; membres avec username) et qualité des téléphones.
-- Lecture seule : uniquement des SELECT. Agrégats uniquement : jamais un téléphone, un email ou un nom.
--
-- Normalisation téléphone (règle PhoneNumbers.normalize de Keevo, appliquée côté Bref) :
--   trim, suppression des espaces / - / . / ( / ), puis préfixe '+' si absent. Un numéro vide après suppression des
--   séparateurs est compté « vide après normalisation » (invalide) et exclu de l export Q4.
-- Préfixe pays connu = ^\+(237|241|235|243|221)[0-9]{6,12}$ (les 5 préfixes acceptés par l OTP Bref). Un numéro stocké
--   sans indicatif (ex. 9 chiffres nationaux) obtient un '+' devant mais ne pourra jamais coïncider avec un numéro Keevo.
-- Rôles : « membres » = admin/seller/viewer. Le rôle superadmin est exclu des membres ET de l export Q4 (comptes internes) ;
--   il est compté explicitement en Q3.d.
-- Schéma : la colonne "user"."emailVerified" n'existe que sur develop (migration 1753780000000) ; sur un réplica de main
--   les colonnes dépendantes sont remplacées par « non mesuré ».
SELECT EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'user' AND column_name = 'emailVerified') AS has_email_verified \gset

\echo
\echo '#### Q3.a — Owners (live) sans téléphone, par provider et présence d email'
\if :has_email_verified
SELECT COALESCE(provider, '(null)') AS provider,
       (email IS NOT NULL)          AS a_un_email,
       "emailVerified"              AS email_verifie,
       COUNT(*)                     AS owners
FROM "user"
WHERE role = 'owner' AND "deletedAt" IS NULL AND (phone IS NULL OR btrim(phone) = '')
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;
\else
\echo 'email_verifie : non mesuré (objet absent : schéma main — colonne emailVerified develop seulement)'
SELECT COALESCE(provider, '(null)') AS provider,
       (email IS NOT NULL)          AS a_un_email,
       COUNT(*)                     AS owners
FROM "user"
WHERE role = 'owner' AND "deletedAt" IS NULL AND (phone IS NULL OR btrim(phone) = '')
GROUP BY 1, 2
ORDER BY 1, 2;
\endif

\echo
\echo '#### Q3.b — Owners (live) : synthèse téléphone / email / social'
\if :has_email_verified
SELECT
  COUNT(*)                                                                      AS owners_live,
  COUNT(*) FILTER (WHERE phone IS NOT NULL AND btrim(phone) <> '')               AS avec_telephone,
  COUNT(*) FILTER (WHERE phone IS NULL OR btrim(phone) = '')                     AS sans_telephone,
  COUNT(*) FILTER (WHERE (phone IS NULL OR btrim(phone) = '') AND email IS NOT NULL) AS sans_tel_avec_email,
  COUNT(*) FILTER (WHERE (phone IS NULL OR btrim(phone) = '') AND email IS NULL) AS sans_tel_sans_email,
  COUNT(*) FILTER (WHERE "socialId" IS NOT NULL)                                 AS avec_social_id,
  COUNT(*) FILTER (WHERE email IS NOT NULL AND "emailVerified")                  AS email_verifie
FROM "user" WHERE role = 'owner' AND "deletedAt" IS NULL;
\else
\echo 'email_verifie : non mesuré (objet absent : schéma main)'
SELECT
  COUNT(*)                                                                      AS owners_live,
  COUNT(*) FILTER (WHERE phone IS NOT NULL AND btrim(phone) <> '')               AS avec_telephone,
  COUNT(*) FILTER (WHERE phone IS NULL OR btrim(phone) = '')                     AS sans_telephone,
  COUNT(*) FILTER (WHERE (phone IS NULL OR btrim(phone) = '') AND email IS NOT NULL) AS sans_tel_avec_email,
  COUNT(*) FILTER (WHERE (phone IS NULL OR btrim(phone) = '') AND email IS NULL) AS sans_tel_sans_email,
  COUNT(*) FILTER (WHERE "socialId" IS NOT NULL)                                 AS avec_social_id
FROM "user" WHERE role = 'owner' AND "deletedAt" IS NULL;
\endif

\echo
\echo '#### Q3.c — Membres (admin/seller/viewer, live) sans téléphone, par rôle et identifiant disponible'
SELECT role,
       CASE
         WHEN username IS NOT NULL AND email IS NOT NULL THEN 'username + email'
         WHEN username IS NOT NULL THEN 'username seul'
         WHEN email IS NOT NULL THEN 'email seul'
         ELSE 'aucun identifiant' END AS identifiant,
       COUNT(*) AS membres
FROM "user"
WHERE role IN ('admin', 'seller', 'viewer') AND "deletedAt" IS NULL AND (phone IS NULL OR btrim(phone) = '')
GROUP BY 1, 2
ORDER BY 1, 2;

\echo
\echo '#### Q3.d — Membres (live) : total, avec / sans téléphone, rattachés à une boutique ; superadmin compté à part (hors membres, hors Q4)'
SELECT role,
       COUNT(*)                                                           AS utilisateurs,
       COUNT(*) FILTER (WHERE phone IS NOT NULL AND btrim(phone) <> '')    AS avec_telephone,
       COUNT(*) FILTER (WHERE phone IS NULL OR btrim(phone) = '')          AS sans_telephone,
       COUNT(*) FILTER (WHERE "shopId" IS NULL)                            AS sans_boutique,
       CASE WHEN role = 'superadmin' THEN 'exclu des membres et de Q4' ELSE 'membre' END AS traitement
FROM "user"
WHERE role IN ('admin', 'seller', 'viewer', 'superadmin') AND "deletedAt" IS NULL
GROUP BY 1 ORDER BY 1;

\echo
\echo '#### Q3.e — Qualité des téléphones (tous rôles, live) : format après normalisation, préfixe pays connu'
WITH norm AS (
  SELECT role, n,
         CASE WHEN left(n, 1) = '+' THEN n ELSE '+' || n END AS phone_norm,
         phone AS brut
  FROM (SELECT role, phone, regexp_replace(btrim(phone), '[[:space:]\-.()]', '', 'g') AS n
        FROM "user" WHERE "deletedAt" IS NULL AND phone IS NOT NULL AND btrim(phone) <> '') t
)
SELECT role,
       COUNT(*)                                                                      AS telephones,
       COUNT(*) FILTER (WHERE n = '')                                                 AS vide_apres_normalisation,
       COUNT(*) FILTER (WHERE n <> '' AND phone_norm ~ '^\+[0-9]{8,15}$')             AS format_e164_ok,
       COUNT(*) FILTER (WHERE n <> '' AND phone_norm !~ '^\+[0-9]{8,15}$')            AS format_invalide,
       COUNT(*) FILTER (WHERE phone_norm ~ '^\+(237|241|235|243|221)[0-9]{6,12}$')    AS prefixe_pays_connu,
       COUNT(*) FILTER (WHERE phone_norm !~ '^\+(237|241|235|243|221)[0-9]{6,12}$')   AS sans_prefixe_connu,
       COUNT(*) FILTER (WHERE phone_norm <> brut)                                     AS modifies_par_normalisation
FROM norm GROUP BY 1 ORDER BY 1;

\echo
\echo '#### Q3.f — Préfixes pays des téléphones (4 premiers caractères après normalisation, owners live)'
SELECT left(CASE WHEN left(n, 1) = '+' THEN n ELSE '+' || n END, 4) AS prefixe, COUNT(*) AS owners
FROM (SELECT regexp_replace(btrim(phone), '[[:space:]\-.()]', '', 'g') AS n
      FROM "user" WHERE role = 'owner' AND "deletedAt" IS NULL AND phone IS NOT NULL AND btrim(phone) <> '') t
WHERE n <> ''
GROUP BY 1 ORDER BY 2 DESC, 1;

\echo
\echo '#### Q3.g — Doublons internes après normalisation (téléphones portés par > 1 utilisateur live)'
SELECT COUNT(*) AS telephones_en_doublon, COALESCE(SUM(n), 0) AS utilisateurs_concernes
FROM (
  SELECT CASE WHEN left(n, 1) = '+' THEN n ELSE '+' || n END AS phone_norm, COUNT(*) AS n
  FROM (SELECT regexp_replace(btrim(phone), '[[:space:]\-.()]', '', 'g') AS n
        FROM "user" WHERE "deletedAt" IS NULL AND phone IS NOT NULL AND btrim(phone) <> '') t
  WHERE n <> ''
  GROUP BY 1 HAVING COUNT(*) > 1
) d;

\echo
\echo '#### Q3.h — Owners live avec suppression programmée (scheduledDeletionAt)'
SELECT COUNT(*) AS owners_suppression_programmee FROM "user"
WHERE role = 'owner' AND "deletedAt" IS NULL AND "scheduledDeletionAt" IS NOT NULL;
