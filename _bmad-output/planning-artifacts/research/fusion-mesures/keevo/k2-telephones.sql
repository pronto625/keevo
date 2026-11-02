-- K2 — Keevo (public.users.phone_number) : préfixes pays et conformité E.164 après normalisation.
-- Lecture seule : uniquement des SELECT. Agrégats uniquement — jamais un numéro.
-- Normalisation = PhoneNumbers.normalize (com.keevo.shared.domain) : trim, suppression espaces / - / . / ( / ), préfixe '+' si absent.
-- Un numéro vide après suppression des séparateurs est compté « vide après normalisation » (invalide, exclu de l export K4).
-- Préfixe pays connu = ^\+(237|241|235|243|221)[0-9]{6,12}$ (préfixes acceptés par l OTP Bref) : seuls ces numéros peuvent coïncider avec Bref.

\echo
\echo '#### K2.a — Téléphones : conformité ^\+[0-9]{8,15}$ après normalisation, préfixe pays connu, numéros modifiés par la normalisation'
WITH norm AS (
  SELECT phone_number AS brut, n,
         CASE WHEN left(n, 1) = '+' THEN n ELSE '+' || n END AS phone_norm
  FROM (SELECT phone_number, regexp_replace(btrim(phone_number), '[[:space:]\-.()]', '', 'g') AS n FROM public.users) t
)
SELECT COUNT(*) AS utilisateurs,
       COUNT(*) FILTER (WHERE n = '')                          AS vide_apres_normalisation,
       COUNT(*) FILTER (WHERE n <> '' AND phone_norm ~ '^\+[0-9]{8,15}$')  AS format_e164_ok,
       COUNT(*) FILTER (WHERE n <> '' AND phone_norm !~ '^\+[0-9]{8,15}$') AS format_invalide,
       COUNT(*) FILTER (WHERE phone_norm ~ '^\+(237|241|235|243|221)[0-9]{6,12}$')  AS prefixe_pays_connu,
       COUNT(*) FILTER (WHERE phone_norm !~ '^\+(237|241|235|243|221)[0-9]{6,12}$') AS sans_prefixe_connu,
       COUNT(*) FILTER (WHERE phone_norm <> brut)              AS modifies_par_normalisation,
       COUNT(DISTINCT phone_norm)                              AS telephones_distincts_normalises
FROM norm;

\echo
\echo '#### K2.b — Préfixes (4 premiers caractères après normalisation)'
SELECT left(CASE WHEN left(n, 1) = '+' THEN n ELSE '+' || n END, 4) AS prefixe, COUNT(*) AS utilisateurs
FROM (SELECT regexp_replace(btrim(phone_number), '[[:space:]\-.()]', '', 'g') AS n FROM public.users) t
GROUP BY 1 ORDER BY 2 DESC, 1;

\echo
\echo '#### K2.c — Doublons après normalisation (un même numéro porté par > 1 utilisateur)'
SELECT COUNT(*) AS telephones_en_doublon, COALESCE(SUM(n), 0) AS utilisateurs_concernes
FROM (
  SELECT CASE WHEN left(n, 1) = '+' THEN n ELSE '+' || n END AS phone_norm, COUNT(*) AS n
  FROM (SELECT regexp_replace(btrim(phone_number), '[[:space:]\-.()]', '', 'g') AS n FROM public.users) t
  GROUP BY 1 HAVING COUNT(*) > 1
) d;

\echo
\echo '#### K2.d — Longueur des numéros normalisés (chiffres après le +)'
SELECT length(CASE WHEN left(n, 1) = '+' THEN n ELSE '+' || n END) - 1 AS chiffres, COUNT(*) AS utilisateurs
FROM (SELECT regexp_replace(btrim(phone_number), '[[:space:]\-.()]', '', 'g') AS n FROM public.users) t
GROUP BY 1 ORDER BY 1;
