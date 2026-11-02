#!/usr/bin/env bash
# run-mesures.sh — Kit de mesures Bref × Keevo (story 18.1), lecture seule, agrégats uniquement.
#
# Usage :
#   BREF_DSN='postgresql://ro_user:***@replica-bref:5432/bref_db' \
#   KEEVO_DSN='postgresql://ro_user:***@replica-keevo:5432/keevo' \
#   [DEVELOP_DEPLOY_DATE=2026-10-26] [REPORT_DATE=YYYY-MM-DD] [API_BREF_DIR=/chemin/api-bref] [BREF_APP_DIR=/chemin/bref] \
#   bash run-mesures.sh
#
# - Chaque fichier SQL est exécuté par psql dans une transaction en lecture seule (PGOPTIONS default_transaction_read_only=on,
#   psql -X -v ON_ERROR_STOP=1) : toute écriture accidentelle fait échouer la run (rapport marqué INCOMPLET, code retour 1).
#   Une DSN contenant `options=` écraserait PGOPTIONS : elle est refusée (code 2).
# - Le rapport (agrégats seulement) est écrit dans out/<date>/rapport-mesures-<date>.md.
# - Q4 : les empreintes md5(sel || numéro normalisé) sont exportées dans out/<date>/*.hashes.csv, comparées localement
#   avec `comm`, puis à SUPPRIMER (jamais commitées : .gitignore). Le sel est tiré au hasard à chaque run et n est jamais écrit.
# - Une DSN absente ⇒ la partie correspondante est marquée « non mesuré », la run continue (avertissement sur stderr) ;
#   aucune DSN ⇒ code 2.
# Codes retour : 0 complet (éventuellement avec avertissements) ; 1 INCOMPLET (une section a échoué) ; 2 prérequis ou entrée invalide.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_DATE="${REPORT_DATE:-$(date +%F)}"
DEVELOP_DEPLOY_DATE="${DEVELOP_DEPLOY_DATE:-2026-10-26}"
OUT_DIR="$HERE/out/$REPORT_DATE"
REPORT="$OUT_DIR/rapport-mesures-$REPORT_DATE.md"
API_BREF_DIR="${API_BREF_DIR:-$HERE/../../../../../api-bref}"
BREF_APP_DIR="${BREF_APP_DIR:-$HERE/../../../../../bref}"
KEEVO_REPO_DIR="${KEEVO_REPO_DIR:-$HERE/../../../..}"

die2() { echo "ERREUR : $*" >&2; exit 2; }

# ── Prérequis et validation des entrées (code 2) ──────────────────────────────────────────────────────────────────
command -v psql >/dev/null || die2 "psql introuvable (client PostgreSQL ≥ 11 requis)"
command -v comm >/dev/null || die2 "comm introuvable (coreutils requis)"
[[ -n "${BREF_DSN:-}" || -n "${KEEVO_DSN:-}" ]] || die2 "aucune DSN fournie : définir BREF_DSN et/ou KEEVO_DSN"
for v in BREF_DSN KEEVO_DSN; do
  if [[ -n "${!v:-}" ]] && printf '%s' "${!v}" | grep -qiE '(^|[?&[:space:]])options='; then
    die2 "$v contient « options= » : cela remplacerait PGOPTIONS et désactiverait le garde-fou lecture seule"
  fi
done
[[ "$DEVELOP_DEPLOY_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] && date -d "$DEVELOP_DEPLOY_DATE" >/dev/null 2>&1 \
  || die2 "DEVELOP_DEPLOY_DATE invalide (attendu YYYY-MM-DD) : $DEVELOP_DEPLOY_DATE"
[[ "$REPORT_DATE" =~ ^[0-9A-Za-z._-]+$ ]] || die2 "REPORT_DATE invalide (autorisé : lettres, chiffres, . _ -) : $REPORT_DATE"
[[ "$HERE" != *" "* ]] || die2 "le chemin du kit contient un espace (incompatible avec \\o de psql) : $HERE"

mkdir -p "$OUT_DIR"

# Garde-fou lecture seule : toutes les sessions psql de ce script démarrent en read-only.
export PGOPTIONS='-c default_transaction_read_only=on'
PSQL_BASE=(psql -X -v ON_ERROR_STOP=1 -q --pset footer=off --pset pager=off)

# Sel de la run pour les empreintes Q4 (jamais écrit dans le rapport).
SALT="$(head -c16 /dev/urandom | od -An -tx1 | tr -d ' \n')"

STATUS_PLACEHOLDER='__STATUT_RUN__'
INCOMPLETE=0
WARNINGS=()

warn() { echo "AVERTISSEMENT : $*" >&2; WARNINGS+=("$*"); }
fail_note() { INCOMPLETE=1; echo "ERREUR : $*" >&2; }

# ── Provenance ───────────────────────────────────────────────────────────────────────────────────────────────────
kit_rev="$(git -C "$KEEVO_REPO_DIR" rev-parse --short HEAD 2>/dev/null || echo 'inconnue')"
kit_dirty='propre'
if [[ -n "$(git -C "$KEEVO_REPO_DIR" status --porcelain -- "$HERE" 2>/dev/null | grep -v '/out/' || true)" ]]; then kit_dirty='modifié localement (non commité)'; fi

# db_provenance <dsn> : version du serveur et horodatage de rejeu (réplica) — rien d autre.
db_provenance() {
  local dsn="$1" out
  if out="$("${PSQL_BASE[@]}" -At -F ' | ' "$dsn" -c "SELECT split_part(version(), ' on ', 1), COALESCE('réplica (dernier rejeu ' || pg_last_xact_replay_timestamp()::text || ')', 'primaire ou dump restauré (pas de rejeu en cours)')" 2>/dev/null)"; then
    echo "$out"
  else
    echo "injoignable au démarrage"
  fi
}

# ── En-tête du rapport (jamais les DSN : elles contiennent des secrets) ─────────────────────────────────────────────
{
  echo "# Rapport de mesures Bref × Keevo — $REPORT_DATE"
  echo
  echo "- Généré par \`_bmad-output/planning-artifacts/research/fusion-mesures/run-mesures.sh\` (story 18.1)."
  echo "- Statut de la run : $STATUS_PLACEHOLDER"
  echo "- Révision du kit (dépôt Keevo) : \`$kit_rev\`, dossier du kit $kit_dirty"
  echo "- Bref (api-bref) : $([[ -n "${BREF_DSN:-}" ]] && echo "DSN fournie — $(db_provenance "$BREF_DSN")" || echo 'non mesuré (BREF_DSN absent)')"
  echo "- Keevo : $([[ -n "${KEEVO_DSN:-}" ]] && echo "DSN fournie — $(db_provenance "$KEEVO_DSN")" || echo 'non mesuré (KEEVO_DSN absent)')"
  echo "- Date de bascule \`develop\` utilisée pour Q7 (\`DEVELOP_DEPLOY_DATE\`) : $DEVELOP_DEPLOY_DATE"
  echo "- Horodatage : $(date -u +'%Y-%m-%dT%H:%M:%SZ') (UTC)"
  echo
  echo "> Agrégats uniquement : aucun téléphone, nom, email, identifiant ni référence de document n'apparaît dans ce rapport."
  echo "> Toutes les requêtes ont tourné dans des transactions PostgreSQL en lecture seule."
  echo "> Les mentions « PROXY » signalent une mesure indirecte (aucune colonne source directe) ; « non mesuré (objet absent) »"
  echo "> signale une table ou colonne absente du schéma interrogé (ex. objets \`develop\` sur un réplica de \`main\`)."
  echo
} > "$REPORT"

# ── Parité du prédicat IN_FORCE avec le code api-bref (avertissement, jamais un échec) ────────────────────────────
check_in_force_parity() {
  local svc="$API_BREF_DIR/src/entitlement/entitlement.service.ts" def="$HERE/bref/_definitions.psql" code kit
  [[ -f "$svc" && -f "$def" ]] || return 0
  code="$(awk '/const SUBSCRIPTION_IN_FORCE = `/{f=1; sub(/.*= `/, ""); } f{ if (index($0, "`")) { sub(/`.*/, ""); print; exit } print }' "$svc" | tr -s '[:space:]' ' ' | sed 's/^ //; s/ $//')"
  kit="$(grep -m1 '^\\set IN_FORCE ' "$def" | sed "s/^\\\\set IN_FORCE '//; s/'\$//; s/\\\\'/'/g" | tr -s '[:space:]' ' ' | sed 's/^ //; s/ $//')"
  if [[ -z "$code" || -z "$kit" ]]; then
    warn "parité IN_FORCE non vérifiable (constante ou définition introuvable)."
  elif [[ "$code" != "$kit" ]]; then
    warn "le prédicat IN_FORCE de bref/_definitions.psql diffère de SUBSCRIPTION_IN_FORCE (entitlement.service.ts) : mettre le kit à jour."
  fi
}
check_in_force_parity

# run_sql <libellé> <dsn> <fichier.sql> : exécute le fichier et insère la sortie dans le rapport (bloc fencé).
run_sql() {
  local label="$1" dsn="$2" file="$3" tmp rc
  tmp="$(mktemp)"
  echo "### $label" >> "$REPORT"
  echo >> "$REPORT"
  echo "Source : \`$(basename "$(dirname "$file")")/$(basename "$file")\`" >> "$REPORT"
  echo >> "$REPORT"
  echo '```text' >> "$REPORT"
  if "${PSQL_BASE[@]}" -v develop_deploy_date="$DEVELOP_DEPLOY_DATE" "$dsn" -f "$file" >"$tmp" 2>&1; then
    rc=0
  else
    rc=$?
  fi
  cat "$tmp" >> "$REPORT"
  if [[ $rc -ne 0 ]]; then
    echo >> "$REPORT"
    echo "**ERREUR psql (code $rc) — section incomplète.**" >> "$REPORT"
    fail_note "$label : psql a échoué (code $rc), voir le rapport."
    grep -E '^(psql|ERROR|ERREUR|FATAL)' "$tmp" >&2 || true
  fi
  echo '```' >> "$REPORT"
  echo >> "$REPORT"
  rm -f "$tmp"
}

# export_hashes <dsn> <fichier.sql> <var=chemin>... : export des empreintes (sortie -At -q, variables psql pour \o).
export_hashes() {
  local dsn="$1" file="$2"; shift 2
  local args=(-v "salt=$SALT") kv
  for kv in "$@"; do args+=(-v "$kv"); done
  if ! "${PSQL_BASE[@]}" -At "${args[@]}" "$dsn" -f "$file" >/dev/null 2>"$OUT_DIR/.hash-export.err"; then
    fail_note "export des empreintes ($(basename "$file")) : $(grep -m1 -E '^(psql|ERROR|FATAL)' "$OUT_DIR/.hash-export.err" || echo 'erreur psql')"
    rm -f "$OUT_DIR/.hash-export.err"
    return 1
  fi
  rm -f "$OUT_DIR/.hash-export.err"
}

sort_c() { LC_ALL=C sort -u -o "$1" "$1"; }
count_lines() { if [[ -f "$1" ]]; then wc -l < "$1" | tr -d ' '; else echo 0; fi; }
inter() { LC_ALL=C comm -12 "$1" "$2" | wc -l | tr -d ' '; }
remove_hashes() { rm -f "$OUT_DIR"/*.hashes.csv; }
hash_reminder() {
  if compgen -G "$OUT_DIR/*.hashes.csv" >/dev/null; then
    echo "RAPPEL : supprimer les empreintes $OUT_DIR/*.hashes.csv (jamais à commiter)." >&2
  fi
}

# ── Partie Bref (Q1–Q8) ────────────────────────────────────────────────────────────────────────────────────────────
echo "## Partie 1 — Base Bref (api-bref) : Q1–Q8" >> "$REPORT"
echo >> "$REPORT"
if [[ -n "${BREF_DSN:-}" ]]; then
  run_sql "Q1 — Owners : total, actifs < 90 j (proxies), payants par plan × moyen de paiement" "$BREF_DSN" "$HERE/bref/q1-owners-plans-paiements.sql"
  run_sql "Q2 — Volumes par table et répartition par owner"                                     "$BREF_DSN" "$HERE/bref/q2-volumes.sql"
  run_sql "Q3 — Identités sans téléphone et qualité des téléphones"                             "$BREF_DSN" "$HERE/bref/q3-identites-sans-telephone.sql"
  run_sql "Q5 — Boutiques hors CM, TVA, montants non entiers"                                   "$BREF_DSN" "$HERE/bref/q5-pays-tva-montants.sql"
  run_sql "Q6 — Crédits de compte et packs SMS"                                                 "$BREF_DSN" "$HERE/bref/q6-credits-sms.sql"
  run_sql "Q7 — Totaux incohérents (avant / jour de la bascule / depuis develop)"               "$BREF_DSN" "$HERE/bref/q7-totaux-incoherents.sql"
  run_sql "Q8 — Owners plan ≠ free sans abonnement en vigueur"                                  "$BREF_DSN" "$HERE/bref/q8-plan-sans-abonnement.sql"
else
  warn "BREF_DSN absent : Q1–Q8 et l'intersection Q4 ne sont pas mesurés."
  echo "_Non mesuré (BREF_DSN absent)._" >> "$REPORT"
  echo >> "$REPORT"
fi

# ── Partie Keevo (public) ─────────────────────────────────────────────────────────────────────────────────────────
echo "## Partie 2 — Base Keevo (schéma public)" >> "$REPORT"
echo >> "$REPORT"
if [[ -n "${KEEVO_DSN:-}" ]]; then
  run_sql "K1 — Tenants, utilisateurs, memberships, schémas kv_%, activité, paiements" "$KEEVO_DSN" "$HERE/keevo/k1-tenants-users-memberships.sql"
  run_sql "K2 — Téléphones : préfixes et conformité E.164"                             "$KEEVO_DSN" "$HERE/keevo/k2-telephones.sql"
else
  warn "KEEVO_DSN absent : la partie Keevo et l'intersection Q4 ne sont pas mesurées."
  echo "_Non mesuré (KEEVO_DSN absent)._" >> "$REPORT"
  echo >> "$REPORT"
fi

# ── Q4 — Intersection des téléphones normalisés (empreintes salées comparées localement) ──────────────────────────
echo "## Partie 3 — Q4 : intersection des téléphones normalisés Bref ∩ Keevo" >> "$REPORT"
echo >> "$REPORT"
echo "Méthode : normalisation \`PhoneNumbers.normalize\` des deux côtés en SQL, export \`md5(sel de la run || numéro normalisé)\` (une empreinte par ligne, distincte, triée ; sel aléatoire non conservé), intersection locale par \`comm -12\`. Bref : owners et membres live avec téléphone (superadmin exclu) ; Keevo : utilisateurs actifs OWNER et EMPLOYEE (SUPER_ADMIN exclu). Les numéros Bref sans indicatif pays ne peuvent pas coïncider (voir Q3.e). Les fichiers \`*.hashes.csv\` restent dans \`out/\` (ignorés par git) et doivent être supprimés après lecture du rapport." >> "$REPORT"
echo >> "$REPORT"
BREF_OWNERS_H="$OUT_DIR/bref-owners.hashes.csv"
BREF_MEMBERS_H="$OUT_DIR/bref-members.hashes.csv"
BREF_ALL_H="$OUT_DIR/bref-all.hashes.csv"
KEEVO_OWNERS_H="$OUT_DIR/keevo-owners.hashes.csv"
KEEVO_EMPLOYEES_H="$OUT_DIR/keevo-employees.hashes.csv"
KEEVO_ALL_H="$OUT_DIR/keevo-all.hashes.csv"
if [[ -n "${BREF_DSN:-}" && -n "${KEEVO_DSN:-}" ]]; then
  q4_ok=1
  export_hashes "$BREF_DSN" "$HERE/bref/q4-export-hashes-telephones.sql" "out_owners=$BREF_OWNERS_H" "out_members=$BREF_MEMBERS_H" || q4_ok=0
  export_hashes "$KEEVO_DSN" "$HERE/keevo/k4-export-hashes-telephones.sql" "out_owners=$KEEVO_OWNERS_H" "out_employees=$KEEVO_EMPLOYEES_H" || q4_ok=0
  if [[ $q4_ok -eq 1 ]]; then
    sort_c "$BREF_OWNERS_H"; sort_c "$BREF_MEMBERS_H"; sort_c "$KEEVO_OWNERS_H"; sort_c "$KEEVO_EMPLOYEES_H"
    LC_ALL=C sort -u "$BREF_OWNERS_H" "$BREF_MEMBERS_H" > "$BREF_ALL_H"
    LC_ALL=C sort -u "$KEEVO_OWNERS_H" "$KEEVO_EMPLOYEES_H" > "$KEEVO_ALL_H"
    {
      echo '| Ensemble | Téléphones distincts (normalisés) |'
      echo '|---|---|'
      echo "| Bref owners (live, avec téléphone) | $(count_lines "$BREF_OWNERS_H") |"
      echo "| Bref membres (live, avec téléphone) | $(count_lines "$BREF_MEMBERS_H") |"
      echo "| Bref owners ∪ membres | $(count_lines "$BREF_ALL_H") |"
      echo "| Keevo OWNER actifs | $(count_lines "$KEEVO_OWNERS_H") |"
      echo "| Keevo EMPLOYEE actifs | $(count_lines "$KEEVO_EMPLOYEES_H") |"
      echo "| Keevo OWNER ∪ EMPLOYEE | $(count_lines "$KEEVO_ALL_H") |"
      echo "| **Bref owners ∩ Keevo (tous)** | **$(inter "$BREF_OWNERS_H" "$KEEVO_ALL_H")** |"
      echo "| **Bref membres ∩ Keevo (tous)** | **$(inter "$BREF_MEMBERS_H" "$KEEVO_ALL_H")** |"
      echo "| Bref (owners ∪ membres) ∩ Keevo (tous) | $(inter "$BREF_ALL_H" "$KEEVO_ALL_H") |"
      echo
      echo 'Matrice des collisions par rôle (nombre de téléphones distincts) :'
      echo
      echo '| Bref \\ Keevo | OWNER actif | EMPLOYEE actif |'
      echo '|---|---|---|'
      echo "| owner | $(inter "$BREF_OWNERS_H" "$KEEVO_OWNERS_H") | $(inter "$BREF_OWNERS_H" "$KEEVO_EMPLOYEES_H") |"
      echo "| membre | $(inter "$BREF_MEMBERS_H" "$KEEVO_OWNERS_H") | $(inter "$BREF_MEMBERS_H" "$KEEVO_EMPLOYEES_H") |"
      echo
      echo "Fichiers d'empreintes à supprimer : \`out/$REPORT_DATE/*.hashes.csv\` ($(ls "$OUT_DIR"/*.hashes.csv | wc -l | tr -d ' ') fichiers)."
      echo
    } >> "$REPORT"
  else
    remove_hashes
    echo "**ERREUR : export des empreintes impossible — intersection non calculée ; fichiers partiels supprimés.**" >> "$REPORT"
    echo >> "$REPORT"
  fi
else
  echo "_Non mesuré ($([[ -z "${BREF_DSN:-}" ]] && echo 'BREF_DSN absent' || echo 'KEEVO_DSN absent'))._" >> "$REPORT"
  echo >> "$REPORT"
fi

# ── Q9 / Q13 — Faits lus dans les dépôts + checklist opérateur ─────────────────────────────────────────────────────
echo "## Partie 4 — Q13 / Q9 : faits lus dans les dépôts (non mesurés en base)" >> "$REPORT"
echo >> "$REPORT"
echo "Ces éléments sont lus dans les clones locaux au moment de la run (aucun \`git fetch\` n'est fait : l'état peut être en retard sur GitHub). Ils ne valent pas confirmation de ce qui tourne réellement en production : voir la checklist." >> "$REPORT"
echo >> "$REPORT"

echo "### Q13 — api-bref : quand \`develop\` a atteint \`main\` (seule branche construite et déployée)" >> "$REPORT"
echo >> "$REPORT"
# resolve_ref <dépôt> <nom> : branche locale, sinon origin/<nom>, sinon vide.
resolve_ref() {
  local repo="$1" name="$2"
  if git -C "$repo" rev-parse --verify -q "refs/heads/$name" >/dev/null; then echo "$name"
  elif git -C "$repo" rev-parse --verify -q "refs/remotes/origin/$name" >/dev/null; then echo "origin/$name"
  fi
}
main_ref=""; dev_ref=""
if [[ -d "$API_BREF_DIR/.git" ]]; then
  main_ref="$(resolve_ref "$API_BREF_DIR" main)"; dev_ref="$(resolve_ref "$API_BREF_DIR" develop)"
fi
if [[ -n "$main_ref" && -n "$dev_ref" ]]; then
  mb="$(git -C "$API_BREF_DIR" merge-base "$main_ref" "$dev_ref" 2>/dev/null || true)"
  mb_fact="$( [[ -n "$mb" ]] && git -C "$API_BREF_DIR" log -1 --format='%h %ad' --date=short "$mb" || echo 'introuvable')"
  last_merge="$(git -C "$API_BREF_DIR" log "$main_ref" --merges -1 --format='%h %ad' --date=short 2>/dev/null || true)"
  head_main="$(git -C "$API_BREF_DIR" log "$main_ref" -1 --format='%h %ad' --date=short)"
  head_dev="$(git -C "$API_BREF_DIR" log "$dev_ref" -1 --format='%h %ad' --date=short)"
  ahead="$(git -C "$API_BREF_DIR" rev-list --count "$main_ref..$dev_ref")"
  wf="$API_BREF_DIR/.github/workflows/docker-publish.yml"
  wf_branches='workflow absent'; wf_image='?'
  if [[ -f "$wf" ]]; then
    wf_branches="$(awk '/^on:/{f=1} f&&/branches:/{g=1;next} g&&/^ *- /{gsub(/^ *- */,"");print;next} g&&!/^ *- /{exit}' "$wf" | tr '\n' ' ' || true)"
    wf_image="$(grep -m1 -E 'IMAGE_NAME:' "$wf" | sed 's/.*IMAGE_NAME:[[:space:]]*//' || true)"
  fi
  {
    echo "| Fait (dépôt local api-bref, refs \`$main_ref\` / \`$dev_ref\`) | Valeur |"
    echo "|---|---|"
    echo "| Point de divergence \`merge-base main develop\` (dernier commit commun = dernier passage de develop en prod) | $mb_fact |"
    echo "| Dernier commit de fusion sur \`main\` | ${last_merge:-aucun} |"
    echo "| Tête de \`main\` | $head_main |"
    echo "| Tête de \`develop\` | $head_dev |"
    echo "| Commits de \`develop\` absents de \`main\` | $ahead |"
    echo "| Workflow GHCR : branches construites | ${wf_branches:-?} |"
    echo "| Image déployée (docker-compose.prod.yml via \`latest\`) | ghcr.io/${wf_image:-?} |"
    echo
    echo "Lecture : la date du point de divergence est la meilleure estimation de la mise en production de \`develop\` (\`DEVELOP_DEPLOY_DATE\` par défaut = 2026-10-26). Les $ahead commits de \`develop\` postérieurs ne sont **pas** déployés tant qu'ils ne sont pas sur \`main\` — dont les règles de statut calculées côté serveur (b957867)."
    echo
  } >> "$REPORT"
else
  warn "dépôt api-bref introuvable ou branches main/develop (locales ou origin/) absentes ($API_BREF_DIR) : faits Q13 non calculés."
  echo "_Dépôt api-bref introuvable (\`API_BREF_DIR=$API_BREF_DIR\`) : faits Q13 non calculés. Valeur connue au 2026-11-02 : dernier passage de develop sur main a3faeb2 le 2026-10-26, 7 commits develop non déployés._" >> "$REPORT"
  echo >> "$REPORT"
fi

echo "### Q9 — Identités d'application déclarées dans les dépôts" >> "$REPORT"
echo >> "$REPORT"
gradle="$KEEVO_REPO_DIR/keevo/app/android/app/build.gradle.kts"
rel="$KEEVO_REPO_DIR/.github/workflows/release-flutter.yml"
if [[ -f "$gradle" ]]; then
  ns="$(grep -m1 -E '^\s*namespace\s*=' "$gradle" | sed -E 's/.*=\s*"([^"]+)".*/\1/' || true)"
  appid="$(grep -m1 -E '^\s*applicationId\s*=' "$gradle" | sed -E 's/.*=\s*"([^"]+)".*/\1/' || true)"
else
  warn "build.gradle.kts introuvable ($gradle) : identité Android Keevo non lue."
  ns='non lu (build.gradle.kts absent)'; appid='non lu (build.gradle.kts absent)'
fi
if [[ ! -f "$rel" ]]; then
  signing='workflow release-flutter.yml absent'
elif grep -q 'keevo-release.keystore' "$rel"; then
  signing='keystore `keevo-release.keystore` décodé depuis les secrets GitHub (ANDROID_KEYSTORE_BASE64), un seul flavor'
else
  signing='workflow release-flutter.yml présent mais sans étape keystore identifiable (à lire à la main)'
fi
bref_json="$BREF_APP_DIR/app.json"
if [[ -f "$bref_json" ]]; then
  json_val() { grep -m1 -E "\"$1\"\s*:" "$bref_json" | sed -E 's/.*:\s*"([^"]*)".*/\1/' || true; }
  bref_fact="package \`$(json_val package)\`, bundle \`$(json_val bundleIdentifier)\`, équipe Apple \`$(json_val appleTeamId)\`, projet EAS \`$(json_val projectId)\` (lu dans \`app.json\`)"
else
  bref_fact='valeur connue au 2026-11-02 : package / bundle `com.brefpoint.app`, équipe Apple `N9534K29PS`, projet EAS `bb0b6dbe-c342-43e4-95cc-2902d7bd4d45` (dépôt bref absent)'
fi
{
  echo "| Fait (dépôts) | Valeur |"
  echo "|---|---|"
  echo "| Keevo \`namespace\` (build.gradle.kts) | ${ns:-?} |"
  echo "| Keevo \`applicationId\` (build.gradle.kts) | ${appid:-?} |"
  echo "| Keevo signature release (workflow) | $signing |"
  echo "| Bref (app.json) | $bref_fact |"
  echo
} >> "$REPORT"

cat >> "$REPORT" <<'EOCHK'
### À confirmer par l'opérateur (hors base, non mesuré ici)

- [ ] **Q13 — GHCR / VPS** : tag `ghcr.io/dsrdesign/api-bref:sha-*` effectivement en service sur le VPS (`docker inspect` du conteneur api-bref, `/opt/api-bref`) et date de ce déploiement ; confirmer que ce `sha` correspond bien au point de divergence ci-dessus. Si la date diffère, relancer ce kit avec `DEVELOP_DEPLOY_DATE=<date réelle>`.
- [ ] **Q9 — Play Console** : nom de package réellement publié (fiche Keevo), état de **Play App Signing** (clé d'upload vs clé de signature gérée par Google), et disponibilité du keystore d'upload pour EAS.
- [ ] **Q9 — Comptes développeur** : compte Google Play développeur disponible (propriétaire, accès), compte Apple Developer (équipe `N9534K29PS` côté Bref) et décision iOS pour Keevo.
- [ ] **Q1 (production)** : la définition « actif < 90 j » retenue en 18.2 parmi les proxies rapportés.
- [ ] **Hygiène** : supprimer `out/<date>/*.hashes.csv` une fois le rapport lu ; ne jamais commiter `out/`.

EOCHK

# ── Statut final ──────────────────────────────────────────────────────────────────────────────────────────────────
if [[ $INCOMPLETE -eq 1 ]]; then
  status='**INCOMPLET** — au moins une section a échoué (voir les blocs marqués ERREUR). Ne pas utiliser ce rapport comme référence.'
elif [[ ${#WARNINGS[@]} -gt 0 ]]; then
  status="complet avec avertissements (${#WARNINGS[@]}) : $(printf '%s ; ' "${WARNINGS[@]}")"
else
  status='complet'
fi
tmp_report="$(mktemp)"
awk -v s="$status" -v p="$STATUS_PLACEHOLDER" '{ i = index($0, p); if (i) { $0 = substr($0, 1, i-1) s substr($0, i+length(p)) } print }' "$REPORT" > "$tmp_report"
mv "$tmp_report" "$REPORT"

echo "Rapport : $REPORT" >&2
if [[ $INCOMPLETE -eq 1 ]]; then
  hash_reminder
  echo "Run INCOMPLÈTE (code 1)." >&2
  exit 1
fi
hash_reminder
echo "Run terminée." >&2
