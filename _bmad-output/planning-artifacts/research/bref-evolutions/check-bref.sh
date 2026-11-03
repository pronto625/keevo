#!/usr/bin/env bash
# check-bref.sh — début de session fusion (story 18.3) : liste les commits Bref non journalisés.
# Lecture seule sur les dépôts Bref : git fetch, aucune écriture, aucun push.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JOURNAL="$HERE/journal-evolutions-bref.md"
AI_DIR="${AI_DIR:-$HERE/../../../../..}"
status=0
for repo in api-bref bref; do
  dir="$AI_DIR/$repo"
  last="$(grep -oE "dernier commit journalisé $repo: [0-9a-f]{7,40}" "$JOURNAL" | awk '{print $NF}' || true)"
  echo "===== $repo"
  if [[ ! -d "$dir/.git" ]]; then echo "dépôt absent : $dir"; status=2; continue; fi
  git -C "$dir" fetch --all --prune -q || { echo "fetch impossible (réseau ?)"; status=2; }
  main="$(git -C "$dir" rev-parse --short origin/main)"
  merge="$(git -C "$dir" log origin/main --merges -1 --format='%h %ad' --date=short)"
  echo "prod (origin/main) : $main — dernier merge : $merge"
  echo "develop en avance sur main : $(git -C "$dir" rev-list --count origin/main..origin/develop) commit(s)"
  if [[ -z "$last" ]]; then echo "aucun dernier commit journalisé trouvé dans le journal"; status=2; continue; fi
  new="$(git -C "$dir" log --reverse --format='%h %ad %s' --date=short "$last..origin/develop")"
  prod="$(grep -oE "prod journalisée $repo: [0-9a-f]{7,40}" "$JOURNAL" | awk '{print $NF}' || true)"
  new_main=""
  if [[ -n "$prod" && "$(git -C "$dir" rev-parse origin/main)" != "$(git -C "$dir" rev-parse "$prod")" ]]; then
    # commits (hors fusions) passés en prod depuis la dernière prod journalisée : colonne « En prod ? » à mettre à jour
    new_main="$(git -C "$dir" log --reverse --no-merges --format='%h %ad %s' --date=short "$prod..origin/main")"
  fi
  if [[ -z "$new$new_main" ]]; then
    echo "rien de nouveau depuis $last"
  else
    status=1
    [[ -n "$new" ]] && { echo "à classer (develop) :"; echo "$new" | sed 's/^/  /'; }
    [[ -n "$new_main" ]] && { echo "passés en prod depuis $prod (mettre « En prod ? » à oui, puis « prod journalisée ») :"; echo "$new_main" | sed 's/^/  /'; }
  fi
done
echo
case $status in
  0) echo "Journal à jour.";;
  1) echo "Des commits sont à classer dans $JOURNAL (puis mettre à jour « dernier commit journalisé »).";;
  *) echo "Contrôle incomplet (voir ci-dessus).";;
esac
exit $status
