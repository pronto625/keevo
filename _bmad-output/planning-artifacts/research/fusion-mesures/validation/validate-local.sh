#!/usr/bin/env bash
# validate-local.sh — Validation comportementale du kit 18.1 sur un Postgres JETABLE (jamais une base réelle).
#
#   1. démarre un conteneur postgres:16-alpine (port $PG_PORT, défaut 5499) ;
#   2. reconstruit le schéma Bref depuis les entités TypeORM de $API_BREF_DIR (`typeorm schema:sync`, branche courante = develop) ;
#   3. charge validation/fixture-bref-synthetique.sql (numéros et identités inventés) ;
#   4. lance run-mesures.sh (partie Keevo si KEEVO_DSN est fournie, ex. keevo_dev local) et vérifie les attendus de la matrice ;
#   5. passe 2 « schéma main » : supprime refresh_token et "user"."emailVerified" dans le jetable, relance, vérifie « non mesuré » ;
#   6. vérifie le garde-fou lecture seule et l absence de données de la fixture dans le rapport ;
#   7. supprime le conteneur et out/validation-* (KEEP=1 pour les conserver).
#
# Usage : [PG_PORT=5499] [API_BREF_DIR=…] [KEEVO_DSN=…] [KEEP=1] bash validation/validate-local.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KIT="$(cd "$HERE/.." && pwd)"
PG_PORT="${PG_PORT:-5499}"
API_BREF_DIR="${API_BREF_DIR:-$KIT/../../../../../api-bref}"
CONTAINER="${CONTAINER:-bref-mesures-pg-validation}"
STAMP="validation-$(date +%Y%m%d-%H%M%S)"
DSN="postgresql://postgres:bref@localhost:$PG_PORT/bref_db"
FAIL=0; PASS=0

ok()   { PASS=$((PASS+1)); echo "  OK    $*"; }
ko()   { FAIL=$((FAIL+1)); echo "  ÉCHEC $*"; }
check() { # check <libellé> <commande…> : succès si la commande réussit
  local label="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$label"; else ko "$label"; fi
}
# section <rapport> <titre> : lignes de la section (jusqu au prochain #### ou fin de bloc)
section() { awk -v t="$2" 'index($0, t){f=1; next} f && (/^#### / || /^```$/){exit} f' "$1"; }
export -f section

cleanup() {
  if [[ "${KEEP:-0}" != "1" ]]; then
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    rm -rf "$KIT"/out/validation-*
  else
    echo "KEEP=1 : conteneur $CONTAINER et $KIT/out/$STAMP* conservés."
  fi
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker requis" >&2; exit 2; }
command -v psql >/dev/null || { echo "psql requis" >&2; exit 2; }
[[ -f "$API_BREF_DIR/src/database/data-source.ts" ]] || { echo "API_BREF_DIR invalide : $API_BREF_DIR" >&2; exit 2; }

echo "== 1. Postgres jetable ($CONTAINER, port $PG_PORT)"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" -p "$PG_PORT:5432" -e POSTGRES_PASSWORD=bref -e POSTGRES_DB=bref_db postgres:16-alpine >/dev/null
for _ in $(seq 1 30); do docker exec "$CONTAINER" pg_isready -U postgres -d bref_db >/dev/null 2>&1 && break; sleep 1; done
sleep 2
psql -X -q -v ON_ERROR_STOP=1 "$DSN" -c 'CREATE EXTENSION IF NOT EXISTS "uuid-ossp";'

echo "== 2. Schéma Bref depuis les entités TypeORM ($API_BREF_DIR, $(git -C "$API_BREF_DIR" rev-parse --short HEAD 2>/dev/null || echo '?'))"
if [[ ! -d "$API_BREF_DIR/node_modules" ]]; then (cd "$API_BREF_DIR" && pnpm install --frozen-lockfile >/dev/null); fi
(cd "$API_BREF_DIR" && DATABASE_URL= DATABASE_HOST=localhost DATABASE_PORT="$PG_PORT" DATABASE_USERNAME=postgres DATABASE_PASSWORD=bref DATABASE_NAME=bref_db \
  pnpm exec typeorm-ts-node-commonjs schema:sync -d src/database/data-source.ts >/dev/null)

echo "== 3. Fixture synthétique"
psql -X -q -v ON_ERROR_STOP=1 "$DSN" -f "$HERE/fixture-bref-synthetique.sql"

echo "== 4. Run du kit (schéma develop)"
REPORT="$KIT/out/$STAMP/rapport-mesures-$STAMP.md"
mkdir -p "$KIT/out"
set +e
BREF_DSN="$DSN" KEEVO_DSN="${KEEVO_DSN:-}" REPORT_DATE="$STAMP" bash "$KIT/run-mesures.sh" 2>"$KIT/out/.validate-stderr"
rc=$?
set -e
mkdir -p "$KIT/out/$STAMP"; mv "$KIT/out/.validate-stderr" "$KIT/out/$STAMP/stderr.txt" 2>/dev/null || true
check "run-mesures.sh termine avec le code 0 (obtenu $rc)" test "$rc" -eq 0
check "rapport présent" test -f "$REPORT"
check "statut « complet »" grep -qE '^- Statut de la run : complet' "$REPORT"
check "Q1.c payants en vigueur = 2"            bash -c "section '$REPORT' 'Q1.c —' | grep -qE '^ *TOTAL *\| *tous moyens *\| *2 *$'"
check "Q1.c expiré non compté (aucune ligne momo)" bash -c "! section '$REPORT' 'Q1.c —' | grep -q momo"
check "Q1.b PROXY 1 = 1, PROXY 2 = 2, PROXY 3 = 1" bash -c "s=\$(section '$REPORT' 'Q1.b —'); echo \"\$s\" | grep -qE 'PROXY 1 .*\| *1 *$' && echo \"\$s\" | grep -qE 'PROXY 2 .*\| *2 *$' && echo \"\$s\" | grep -qE 'PROXY 3 .*\| *1 *$'"
check "Q3 owners sans téléphone = 1 (google)"  bash -c "section '$REPORT' 'Q3.a —' | grep -qE '^ *google *\| *t *\| *f *\| *1 *$'"
check "Q3 membres sans téléphone = 1 (username seul)" bash -c "section '$REPORT' 'Q3.c —' | grep -qE '^ *seller *\| *username seul *\| *1 *$'"
check "Q3.d superadmin compté à part"           bash -c "section '$REPORT' 'Q3.d —' | grep -qE '^ *superadmin *\| *1 .*exclu'"
check "Q3.e owner : 1 vide après normalisation" bash -c "section '$REPORT' 'Q3.e —' | grep -E '^ *owner ' | awk -F'|' '{gsub(/ /,\"\",\$3); exit \$3!=1}'"
check "Q3.e seller : 1 sans préfixe connu"      bash -c "section '$REPORT' 'Q3.e —' | grep -E '^ *seller ' | awk -F'|' '{gsub(/ /,\"\",\$7); exit \$7!=1}'"
check "Q5.b boutiques hors CM = 1"              bash -c "section '$REPORT' 'Q5.b —' | grep -qE '^ *1 *\| *1 *\| *1 *$'"
check "Q5.c CM : 1 TVA ignorée, remise totale conforme" bash -c "section '$REPORT' 'Q5.c —' | grep -E '^ *CM ' | awk -F'|' '{gsub(/ /,\"\",\$5); gsub(/ /,\"\",\$6); exit !(\$5==1 && \$6==0)}'"
check "Q5.e invoice.subtotal décimal = 1"       bash -c "section '$REPORT' 'Q5.e —' | grep -qE '^ *invoice.subtotal *\| *1 *\|'"
check "Q7.a avant develop : 2 ventes, 1 incohérente" bash -c "section '$REPORT' 'Q7.a —' | grep -qE '^ *avant develop *\| *sale *\| *live *\| *2 *\| *1 *\|'"
check "Q7.a jour de la bascule : 1 document, 0 incohérent" bash -c "section '$REPORT' 'Q7.a —' | grep -qE '^ *jour de la bascule *\| *sale *\| *live *\| *1 *\| *0 *\|'"
check "Q8.b = 1 (pro, expired, momo)"           bash -c "section '$REPORT' 'Q8.b —' | grep -qE '^ *pro *\| *expired *\| *momo *\| *1 *$'"
check "Q8.c expiré < 30 j = 1"                  bash -c "section '$REPORT' 'Q8.c —' | grep -qE '^ *expiré < 30 j *\| *1 *$'"
if [[ -n "${KEEVO_DSN:-}" ]]; then
  check "Q4 : Bref owners exportés = 4, membres = 1" bash -c "grep -qE '^\| Bref owners \(live, avec téléphone\) \| 4 \|' '$REPORT' && grep -qE '^\| Bref membres \(live, avec téléphone\) \| 1 \|' '$REPORT'"
  check "Q4 : matrice 2×2 présente"               grep -q 'Matrice des collisions par rôle' "$REPORT"
  check "Q4 : fichiers d empreintes présents (6)"  bash -c "test \$(ls '$KIT/out/$STAMP'/*.hashes.csv | wc -l) -eq 6"
else
  echo "  INFO  KEEVO_DSN absent : Q4 et partie Keevo non vérifiés (attendu : « non mesuré »)."
  check "Q4 marqué non mesuré"                  grep -q 'Non mesuré (KEEVO_DSN absent)' "$REPORT"
fi
check "aucune donnée de la fixture dans le rapport" bash -c "! grep -qiE 'fixture-|example\.test|vendeur\.|Client [XYZWVU]|Shop [AE]1|Ville [AE]|699 ?01 ?77|650 ?00 ?00|-(1111|2222|3333|4444|5555|6666|7777|8888|9999)-|a1a1a1a1|e1e1e1e1|f[1-6]f[1-6]f[1-6]f[1-6]|jti-fixture|fixture-push' '$REPORT'"
check "le sel n apparaît pas dans le rapport"   bash -c "! grep -qE '[0-9a-f]{32}' '$REPORT'"

echo "== 5. Passe 2 : schéma « main » (sans refresh_token ni emailVerified)"
psql -X -q -v ON_ERROR_STOP=1 "$DSN" -c 'DROP TABLE refresh_token;' -c 'ALTER TABLE "user" DROP COLUMN "emailVerified";'
REPORT2="$KIT/out/$STAMP-main/rapport-mesures-$STAMP-main.md"
set +e
BREF_DSN="$DSN" KEEVO_DSN= REPORT_DATE="$STAMP-main" bash "$KIT/run-mesures.sh" 2>/dev/null
rc2=$?
set -e
check "run sur schéma main : code 0 (obtenu $rc2)" test "$rc2" -eq 0
check "objets develop marqués « non mesuré (objet absent) » (≥ 4 mentions)" bash -c "test \$(grep -c 'non mesuré (objet absent' '$REPORT2') -ge 4"
check "Q1.b PROXY 2 et 3 toujours calculés"     bash -c "section '$REPORT2' 'Q1.b —' | grep -qE 'PROXY 2 .*\| *2 *$' && section '$REPORT2' 'Q1.b —' | grep -qE 'union 2\+3'"
check "Q3.b toujours imprimé sans email_verifie" bash -c "section '$REPORT2' 'Q3.b —' | grep -qE '^ *[0-9]+ *\| *[0-9]+ *\| *1 *\|'"

echo "== 6. Garde-fou lecture seule et entrées invalides"
check "UPDATE refusé sous PGOPTIONS read-only" bash -c "PGOPTIONS='-c default_transaction_read_only=on' psql -X -v ON_ERROR_STOP=1 -q '$DSN' -c 'UPDATE \"user\" SET plan = plan WHERE false' 2>&1 | grep -q 'read-only transaction'"
check "aucune DSN ⇒ code 2"                    bash -c "BREF_DSN= KEEVO_DSN= bash '$KIT/run-mesures.sh' >/dev/null 2>&1; test \$? -eq 2"
check "DSN avec options= ⇒ code 2"             bash -c "BREF_DSN='$DSN?options=-c%20x' bash '$KIT/run-mesures.sh' >/dev/null 2>&1; test \$? -eq 2"
check "DEVELOP_DEPLOY_DATE invalide ⇒ code 2"  bash -c "BREF_DSN='$DSN' DEVELOP_DEPLOY_DATE=hier bash '$KIT/run-mesures.sh' >/dev/null 2>&1; test \$? -eq 2"
check "REPORT_DATE invalide ⇒ code 2"          bash -c "BREF_DSN='$DSN' REPORT_DATE='a b' bash '$KIT/run-mesures.sh' >/dev/null 2>&1; test \$? -eq 2"
check "DSN injoignable ⇒ code 1 (INCOMPLET)"   bash -c "BREF_DSN='postgresql://postgres:bref@nohost.invalid:5499/bref_db' REPORT_DATE='$STAMP-unreach' bash '$KIT/run-mesures.sh' >/dev/null 2>&1; test \$? -eq 1"
check "grep d écriture sur bref/ et keevo/ vide" bash -c "! grep -rniE '^\s*(insert|update|delete|create|alter|drop|truncate)\b' '$KIT/bref' '$KIT/keevo'"

echo
echo "== Résultat : $PASS OK, $FAIL échec(s)"
[[ $FAIL -eq 0 ]]
