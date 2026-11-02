-- K4 — Export des empreintes salées des téléphones normalisés Keevo (public.users), pour l intersection locale avec Bref (Q4).
-- Lecture seule : uniquement des SELECT ; la sortie est redirigée par \o vers des fichiers *.hashes.csv du dossier de sortie
-- (ignorés par git, à supprimer après la run — voir README).
--
-- Variables psql attendues (fournies par run-mesures.sh, avec -At -q) :
--   :salt           sel aléatoire de la run (identique à l export Bref, jamais écrit dans le rapport)
--   :out_owners     empreintes des utilisateurs role = 'OWNER', is_active
--   :out_employees  empreintes des utilisateurs role = 'EMPLOYEE', is_active
--   (SUPER_ADMIN et utilisateurs inactifs exclus : comptes internes / désactivés, pas des identités à réconcilier)
--
-- Normalisation = PhoneNumbers.normalize : trim, suppression espaces / - / . / ( / ), préfixe '+' si absent.
-- Un numéro vide après suppression des séparateurs est exclu (normalize renvoie '' ; hacher '+' créerait une fausse collision).
-- Une empreinte par ligne, distincte, triée (le runner retrie en LC_ALL=C avant `comm`). Aucun numéro en clair n est écrit.

\o :out_owners
SELECT DISTINCT md5(:'salt' || CASE WHEN left(n, 1) = '+' THEN n ELSE '+' || n END) AS h
FROM (SELECT regexp_replace(btrim(phone_number), '[[:space:]\-.()]', '', 'g') AS n
      FROM public.users WHERE role = 'OWNER' AND is_active) t
WHERE n <> ''
ORDER BY 1;
\o

\o :out_employees
SELECT DISTINCT md5(:'salt' || CASE WHEN left(n, 1) = '+' THEN n ELSE '+' || n END) AS h
FROM (SELECT regexp_replace(btrim(phone_number), '[[:space:]\-.()]', '', 'g') AS n
      FROM public.users WHERE role = 'EMPLOYEE' AND is_active) t
WHERE n <> ''
ORDER BY 1;
\o
