-- Q4 — Export des empreintes salées des téléphones normalisés Bref, pour l intersection locale avec Keevo.
-- Lecture seule : uniquement des SELECT ; la sortie est redirigée par \o vers des fichiers *.hashes.csv
-- dans le dossier de sortie (ignorés par git, à supprimer après la run — voir README).
--
-- Variables psql attendues (fournies par run-mesures.sh, avec -At -q) :
--   :salt         sel aléatoire de la run (32 hex), identique pour les exports Bref et Keevo, jamais écrit dans le rapport :
--                 md5(sel || numéro) n est pas inversible par énumération des numéros sans le sel.
--   :out_owners   chemin du fichier des empreintes owners (live, role = 'owner')
--   :out_members  chemin du fichier des empreintes membres (live, role IN admin/seller/viewer ; superadmin exclu)
--
-- Normalisation = règle PhoneNumbers.normalize (Keevo) : trim, suppression espaces / - / . / ( / ), préfixe '+' si absent.
-- Un numéro vide après suppression des séparateurs est exclu (Java normalize renvoie '' ; hacher '+' créerait une fausse collision).
-- Les numéros sans indicatif pays (voir Q3.e « sans_prefixe_connu ») sont exportés mais ne peuvent pas coïncider avec Keevo.
-- Une empreinte par ligne, distincte, triée (le runner retrie en LC_ALL=C avant `comm`). Aucun téléphone en clair n est écrit.

\o :out_owners
SELECT DISTINCT md5(:'salt' || CASE WHEN left(n, 1) = '+' THEN n ELSE '+' || n END) AS h
FROM (SELECT regexp_replace(btrim(phone), '[[:space:]\-.()]', '', 'g') AS n
      FROM "user"
      WHERE role = 'owner' AND "deletedAt" IS NULL AND phone IS NOT NULL AND btrim(phone) <> '') t
WHERE n <> ''
ORDER BY 1;
\o

\o :out_members
SELECT DISTINCT md5(:'salt' || CASE WHEN left(n, 1) = '+' THEN n ELSE '+' || n END) AS h
FROM (SELECT regexp_replace(btrim(phone), '[[:space:]\-.()]', '', 'g') AS n
      FROM "user"
      WHERE role IN ('admin', 'seller', 'viewer') AND "deletedAt" IS NULL AND phone IS NOT NULL AND btrim(phone) <> '') t
WHERE n <> ''
ORDER BY 1;
\o
