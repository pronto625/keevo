# Runbook de la bascule Bref → Keevo (ADR-10)

> **Statut (11/11/2026).** Runbook complet de la bascule unique : calendrier J-10 → J+7 (§0), liens publics (§1, story
> 24.10), webhooks (§2, 24.10), commande de bascule (§3, 24.9), préparation J-10 → J-7 (§4), veille J-1 (§5), journée
> J0 pas à pas (§6), owner en écart (§7), retour arrière (§8), contrôles J+1 (§9) et J+7 (§10), messages fr / en (§11),
> contacts (§12), actions utilisateur (§13), fiche de chronométrage (§14). Story **24.13**.
>
> **Durées.** Les durées données ici sont des **estimations** tirées des mesures de 24.9 (§3.4 : ≈ 6,8 s par owner au
> parallélisme 4, poste de développement). La durée de coupure **annoncée** se fixe seulement après la répétition
> générale **24.12** sur une copie de la prod Bref (qui attend un dump de prod fourni par l'utilisateur) : tant que la
> fiche §14 n'est pas remplie par 24.12, ce runbook n'est **pas validé pour la production**.
>
> **Rien n'est appliqué automatiquement.** Chaque étape marquée **[UTILISATEUR]** se fait à la main, sur un serveur ou
> dans le tableau de bord d'un fournisseur. Aucun message ne part tout seul : chaque campagne est créée en brouillon,
> relue, puis envoyée par un clic ou un appel explicite. Aucun secret, jeton ni mot de passe n'est écrit ici : les
> valeurs `<…>` sont à remplacer au moment de l'exécution et ne doivent jamais être recopiées dans un fichier versionné.
>
> **Données sensibles.** Le dump de Bref, la base restaurée et tout fichier d'export d'owner (source `EXPORT`)
> contiennent les données personnelles des clients **et les jetons Meta (WhatsApp Business) des owners en clair** (Bref
> ne les chiffre pas ; Keevo les rechiffre à l'import). Ces fichiers restent sur les serveurs (jamais sur un poste
> personnel, jamais dans un dépôt, une messagerie ou un ticket), en `chmod 600`, et sont supprimés selon §10.

## 0. Vue d'ensemble

### 0.1 Calendrier

| Moment | Quoi | Qui | Section |
|---|---|---|---|
| J-10 | Modèles WhatsApp soumis à Meta ; contrôles Apple / Google ; app React Native soumise aux stores en « publication manuelle » | utilisateur | 4.1–4.3 |
| J-10 → J-7 | Répétition générale 24.12 terminée, fiche §14 remplie, heure d'arrêt et heure limite de retour fixées | équipe | 4.4 |
| J-7 → J-1 | Owners en cas bloquant (relevés par 24.12) contactés et corrigés pendant que Bref tourne encore | support | 4.5 |
| J-7 | Annonce (WhatsApp, SMS, notification, e-mail ; fr et en) par les campagnes de l'admin Bref | super-admin Bref | 4.6, 11 |
| J-2 | TTL DNS baissé (si option B du §1.3), gel des déploiements Keevo et Bref | utilisateur | 5.1 |
| J-1 | Rappel ; vérifications finales ; réunion go / no-go préparée | super-admin, équipe | 5 |
| J0 T0 | Arrêt d'api-bref, dump, restauration, index, rôle en lecture seule, réglage du backend | exploitant | 6.1–6.7 |
| J0 | Commande de bascule, webhooks repointés pendant l'import, lecture du rapport, cas à traiter | super-admin, utilisateur | 6.8–6.12, 7 |
| J0 | Décision « go » / « no-go » sur critères écrits | responsable de la bascule | 6.13 |
| J0 après « go » | Activation globale, redirection des liens, publication de l'app, message « c'est fait » | super-admin, utilisateur | 6.14–6.18 |
| J0 si « no-go » | Retour arrière, Bref rallumé, message d'incident | exploitant | 8 |
| J+1 | Contrôles du lendemain | équipe | 9 |
| J+7 | Jalon G4, nettoyage (copie, dumps, rôle, clés temporaires) | équipe, utilisateur | 10 |

### 0.2 Durée de coupure (estimation à confirmer par 24.12)

`N` = nombre d'owners Bref (compté par 24.12 : `SELECT count(*) FROM "user" WHERE role = 'owner'` sur la copie).

| Étape J0 | Estimation | Source |
|---|---|---|
| Arrêt d'api-bref (6.1) | 1 min | — |
| Dump (6.2) | 2–10 min | base « de petite taille » (mesures 18.1) ; mesuré par 24.12 |
| Transfert vers le serveur Keevo (6.3) | 1–5 min | dépend de la taille du dump |
| Restauration + index + `ANALYZE` (6.4–6.5) | 5–15 min | mesuré par 24.12 |
| Rôle, réglage et redémarrage du backend (6.6–6.7) | 5–10 min | redémarrage ≈ 1–2 min |
| Commande de bascule (6.8) | `N × 6,8 s ÷ 4` + owner le plus lourd (≤ 30 s) : 200 owners ≈ 6 min, 1 000 ≈ 29 min | 24.9 §3.4 |
| Lecture du rapport, cas, décision (6.10–6.13) | 15–45 min | — |
| « go » (6.14) | < 1 min (5 ms mesurés) | 24.9 |
| Redirection des liens + contrôles (6.15) | 10–15 min | 24.10 |
| **Total jusqu'au « go »** | ≈ **1 h 30 + durée de la commande** | à confirmer |
| Retour arrière complet (§8) | ≈ 0,2 s par owner + 15 min (webhooks, proxy, Bref rallumé) | 24.9 (34 s pour 202 owners) |

Règle de fenêtre : **fenêtre annoncée ≥ 2 × (total estimé jusqu'au « go ») + durée du retour arrière**, de nuit ou au
moment le plus creux mesuré par 24.12. Exemple de forme (pas une date) : arrêt `<JOUR> à <HEURE_ARRET>`, retour au plus
tard `<JOUR+1> à <HEURE_RETOUR>`. **Heure limite de décision** = heure de retour annoncée − (retour arrière + 30 min) :
passé cette heure sans « go », on fait le retour arrière (§8).

### 0.3 Rôles

- **Responsable de la bascule** : décide le « go » / « no-go » (§6.13) et signe les owners acceptés.
- **Exploitant** : accès SSH aux serveurs Bref et Keevo (étapes `docker`, `pg_dump`, `psql`).
- **Super-admin Keevo** : appels `/api/v1/admin/migration/…` et campagnes Keevo (tableau de bord super-admin ou `curl`).
- **Super-admin Bref** : campagnes de l'admin Bref (annonce, rappel, incident avant le « go »).
- **Support** : répond aux owners (WhatsApp plateforme, téléphone), traite les cas bloquants avec eux.

Une même personne peut tenir plusieurs rôles ; les noms et numéros sont au §12.

### 0.4 Conventions des commandes

```bash
# Sur le POSTE du super-admin, dans bash (lancer `bash` d'abord si le terminal est en zsh) ; rien n'est écrit sur disque
API=https://keevo.ad2s.cm
login() {   # connexion en deux temps : identifiants, puis choix de l'espace super-admin (KV-ADMIN)
  read -rp 'Téléphone du super-admin : ' PHONE; read -rsp 'Mot de passe : ' PASS; echo
  LT=$(curl -s -X POST "$API/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg p "$PHONE" --arg w "$PASS" '{phoneNumber: $p, password: $w}')" | jq -r '.loginToken')
  TOKEN=$(curl -s -X POST "$API/api/v1/auth/select-tenant" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg t "$LT" '{loginToken: $t, tenantCode: "KV-ADMIN"}')" | jq -r '.accessToken')
  unset PASS LT
  if [ -z "$TOKEN" ] || [ "$TOKEN" = null ]; then echo "Connexion refusée : relancer login"; return 1; fi
  H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
}
login     # le jeton dure 30 min : relancer « login » dès qu'un appel répond 401
```

**Où tourne chaque bloc.** « (poste) » = le poste du super-admin, qui détient `$API`, `$TOKEN`, `$H` et `$CUTOVER` ;
« (serveur Keevo) » / « (serveur Bref) » = une session SSH de l'exploitant, qui ne reçoit **jamais** le jeton. Seules des
listes d'identifiants (pas de secret) passent du serveur au poste, par `scp`.

`<…>` : valeur à saisir. Les réponses de l'API d'administration sont enveloppées dans `{"data":…}` (pas celles de la
connexion) ; une erreur porte son message français dans
`error` et son code dans `code`. Les identifiants techniques (`ownerId`, `batchId`, `id` de bascule) servent aux appels,
jamais à un message envoyé à un owner.

## 1. Liens publics Bref (story 24.10)

### 1.1 Forme exacte des anciens liens (relevée dans api-bref `develop`)

- Chemin : `GET /api/v1/invoices/public/<jeton>` sur l'hôte de l'API Bref. Le préfixe `api` vient de `API_PREFIX`
  (`.env.example` d'api-bref), la version `v1` du versionnement d'URI de NestJS (`src/main.ts`).
- Jeton : l'UUID `invoice."publicToken"` (unique), valable 90 jours (`publicTokenExpiresAt`), changé quand la facture
  est annulée ou quand le propriétaire régénère le lien. Bref répondait en JSON (vue restreinte de la facture) et
  401 « lien expiré » une fois la date passée.
- Le sous-chemin `GET /api/v1/invoices/public/count?identifier=…` n'est **pas** un lien : c'est un appel de l'app,
  il n'est pas redirigé.
- Ni l'app Bref (`bref` `develop`) ni les messages d'api-bref (facture WhatsApp/e-mail : PDF joint +
  `https://brefpoint.app/download`) n'écrivent ce lien : il a pu être partagé par le tableau de bord web Bref
  (`admin.brefpoint.app`, dépôt non présent ici) ou copié à la main. Le volume réel est donc probablement faible ;
  la répétition 24.12 le mesure (`SELECT count(*) FROM public.invoice_legacy_links` côté Keevo).

### 1.2 Ce que fait Keevo (code livré par 24.10)

- `GET https://keevo.ad2s.cm/api/v1/invoices/public/<jeton>` (même chemin que Bref) :
  - jeton connu (importé par 24.4), entreprise active, facture qui porte encore ce jeton → **302** vers le lien signé
    Keevo du document (`/api/v1/invoices/shared?token=…`), qui affiche le PDF avec la référence `BRF-…` à côté du
    numéro. Le lien signé ne dure jamais plus longtemps que l'expiration Bref d'origine. Réponse jamais mise en cache.
  - jeton inconnu, mal formé, retiré (lien régénéré dans Keevo), facture supprimée ou entreprise pas encore activée
    → 404 ; jeton expiré → 410 ; trop d'ouvertures → 429.
  - dans un navigateur, ces erreurs s'affichent comme une page française simple (« Lien introuvable », « Lien
    expiré », « Trop de tentatives », « Service momentanément indisponible ») sans aucun numéro, jeton ni code.
- Prérequis côté Keevo : `KEEVO_PUBLIC_BASE_URL=https://keevo.ad2s.cm` dans l'environnement de prod (c'est la base
  de l'adresse de redirection). À vérifier avant J0 :
  `docker exec keevo_backend printenv KEEVO_PUBLIC_BASE_URL`. Sans cette valeur, l'adresse de redirection serait
  déduite des en-têtes de la requête.
- Prérequis réseau : le port du backend (4500) ne doit être joignable **que** par Traefik (aucun port publié sur
  l'hôte) : la limite « 20 liens inconnus par adresse » lit l'adresse client dans `X-Forwarded-For` posé par le
  proxy. Contrôle depuis l'extérieur : `curl -m 5 http://<IP du VPS>:4500/actuator/health` doit échouer.

### 1.3 [UTILISATEUR] Avant J0 : confirmer l'hôte Bref

1. Lire `BACKEND_DOMAIN` dans le `.env` de production d'api-bref. L'analyse du 31/10 indique `api.brefpoint.app` ;
   si la valeur diffère, remplacer `api.brefpoint.app` dans `keevo/ops/traefik/bref-legacy-links.yml` (ou dans la
   configuration nginx ci-dessous).
2. Repérer qui termine le TLS de cet hôte aujourd'hui (Traefik du VPS de Bref : api-bref est sur le réseau Docker
   `vps-network`, `docker-compose.prod.yml` d'api-bref). Deux options :
   - **A (recommandée)** : laisser le DNS sur le VPS de Bref et installer la règle dans **son** proxy (rien d'autre ne
     change sur ce serveur ; api-bref reste arrêté et rallumable) ;
   - **B** : repointer l'enregistrement DNS de l'hôte vers le VPS de Keevo et installer la règle dans le Traefik de
     Keevo (`traefik:v3.3`, fichier unique `~/keevo/traefik/dynamic.yml`). Prévoir la propagation DNS (TTL baissé à
     300 s à J-2) et le certificat Let's Encrypt de l'hôte (premier appel HTTPS).

### 1.4 [UTILISATEUR] J0, après le « go » : installer la redirection

**Traefik (v3)** — fusionner les blocs de `keevo/ops/traefik/bref-legacy-links.yml` (router
`bref-legacy-invoice-links` sous `http.routers`, middleware `bref-legacy-invoice-redirect` sous `http.middlewares`)
dans la configuration dynamique du Traefik choisi en 1.3. Pour le Traefik de Keevo (fichier unique), suivre
exactement `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` §15 étape 3 : sauvegarde
(`cp ~/keevo/traefik/dynamic.yml ~/keevo/traefik/dynamic.yml.bak-24-10`), écriture **en place**
(`cat merged.yml > ~/keevo/traefik/dynamic.yml`), sinon `docker restart <traefik>`. Un Traefik v2 utilise
`Path(\`/api/v1/invoices/public/{token:[0-9a-fA-F-]{36}}\`) || Path(\`/v1/invoices/public/{token:[0-9a-fA-F-]{36}}\`)`
à la place de `PathRegexp`. Un lien avec des paramètres (`?fbclid=…`, `?utm_…`) est redirigé aussi (paramètres
abandonnés). Les liens en `http://` : vérifier que le Traefik choisi redirige le port 80 vers https (c'est le cas de
celui de Keevo), sinon ajouter `web` aux `entryPoints` du router.

**nginx (équivalent)** — dans le bloc `server` de l'hôte Bref (TLS déjà en place) :

```nginx
# Story 24.10 — anciens liens publics Bref → Keevo (302 jusqu'à 25.1 : un 301 survivrait à un retour arrière)
location ~ "^(/api)?/v1/invoices/public/(?<bref_token>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$" {
    add_header Cache-Control "no-store" always;
    return 302 https://keevo.ad2s.cm/api/v1/invoices/public/$bref_token;
}
```

Garder **302** jusqu'à la story 25.1 (retrait définitif d'api-bref) : un 301 est gardé en cache par les navigateurs
et continuerait d'envoyer vers Keevo après un retour arrière.

### 1.5 [UTILISATEUR] Contrôles (5 liens réels, comme la réconciliation 24.8)

```bash
# Sur la base Keevo (lecture seule) : 5 jetons importés encore valides, d'entreprises actives
docker exec keevo_postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT l.token, l.tenant_schema, l.invoice_id FROM public.invoice_legacy_links l \
  JOIN public.tenants t ON t.schema_name = l.tenant_schema AND t.status = 'ACTIVE' \
  WHERE l.expires_at IS NULL OR l.expires_at > now() ORDER BY random() LIMIT 5"

HOTE_BREF=api.brefpoint.app
for T in <jeton1> <jeton2> <jeton3> <jeton4> <jeton5>; do
  curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" "https://$HOTE_BREF/api/v1/invoices/public/$T"
  # attendu : 302 https://keevo.ad2s.cm/api/v1/invoices/public/<jeton>
  curl -sL -o /dev/null -w "%{http_code} %{content_type} (%{num_redirects} redirections)\n" \
       "https://$HOTE_BREF/api/v1/invoices/public/$T"
  # attendu : 200 application/pdf (2 redirections)
done

# Jeton inconnu vu par un navigateur : page française, statut 404, aucun détail
curl -sL -H 'Accept: text/html' -w "\n%{http_code}\n" \
     "https://$HOTE_BREF/api/v1/invoices/public/00000000-0000-4000-8000-000000000000" | grep -E '<h1>|^[0-9]{3}$'
# attendu : <h1>Lien introuvable</h1> puis 404

# Le reste de l'ancienne API n'est pas redirigé
curl -s -o /dev/null -w "%{http_code}\n" "https://$HOTE_BREF/api/v1/invoices/public/count?identifier=x"
# attendu : pas 302 (api-bref arrêté : 502/503/404 du proxy)
```

Un 404 sur un des 5 jetons tirés au sort n'est pas forcément une anomalie : le lien a pu être régénéré dans Keevo
après l'import ou la facture supprimée (le jeton est alors retiré de la facture). Le vérifier dans le schéma de
l'entreprise (`SELECT legacy_public_token = '<jeton>', deleted_at FROM "<schéma>".invoices WHERE id = '<invoice_id>'`)
et tirer un autre jeton ; seul un 404 sur une facture qui porte encore le jeton est un écart.

Ouvrir aussi un des 5 liens sur un téléphone (navigateur, pas l'app) : le PDF s'affiche avec `BRF-…`.

### 1.6 Retour arrière (avant le « go » uniquement)

Retirer le router et le middleware (Traefik de Keevo, fichier unique : réécrire en place la sauvegarde
`cat ~/keevo/traefik/dynamic.yml.bak-24-10 > ~/keevo/traefik/dynamic.yml`) ou le bloc nginx, recharger le proxy ; si l'option B a été prise, remettre
l'enregistrement DNS sur le VPS de Bref ; rallumer api-bref : il répond de nouveau à ses liens.

## 2. Webhooks des fournisseurs (actions utilisateur, pas de routeur à double livraison — ADR-10)

### 2.1 URL à changer

`<hôte Bref>` = la valeur confirmée en 1.3 (`api.brefpoint.app` attendu). Keevo : `https://keevo.ad2s.cm`.

| Fournisseur | Où la changer | URL Bref actuelle (api-bref `develop`) | Nouvelle URL Keevo | Authentification à reprendre |
|---|---|---|---|---|
| **RevenueCat** (App Store / Google Play) | Tableau de bord RevenueCat → projet → *Integrations* → *Webhooks* | `https://<hôte Bref>/webhook/revenuecat` (exclu du préfixe `api`, `src/main.ts`) | `https://keevo.ad2s.cm/api/v1/payments/webhook/revenuecat` | En-tête `Authorization` du webhook = `REVENUECAT_WEBHOOK_SECRET` de Keevo (reprendre la valeur de Bref, même nom de variable). Même projet RevenueCat : `REVENUECAT_SECRET_KEY`, `REVENUECAT_PROJECT_ID` repris tels quels. |
| **MyCoolPay** (Mobile Money) | Espace marchand MyCoolPay → application → URL de notification (callback) | `https://<hôte Bref>/api/webhook/pay/mycoolpay/<MYCOOLPAY_CALLBACK_HASH>` (`src/payment-gateway/payment-gateway-webhook.controller.ts` ; le préfixe `api` s'applique — la doc d'api-bref dit le contraire : **recopier l'URL affichée dans l'espace marchand**) | `https://keevo.ad2s.cm/api/v1/payments/webhook/mycoolpay/<MYCOOLPAY_CALLBACK_HASH>` | Keevo : `MYCOOLPAY_PUBLIC_KEY`, `MYCOOLPAY_PRIVATE_KEY`, `MYCOOLPAY_CALLBACK_HASH` = valeurs de Bref ; `KEEVO_PAYMENTS_GATEWAY=mycoolpay`. |
| **Meta — WhatsApp Cloud** | Meta for Developers → app → *WhatsApp* → *Configuration* → *Webhook* (URL de rappel + jeton de vérification, puis « Vérifier et enregistrer ») | `https://<hôte Bref>/webhook/whatsapp` (GET vérification + POST événements, exclu du préfixe) | `https://keevo.ad2s.cm/api/v1/whatsapp/webhook` | Jeton de vérification = `KEEVO_WHATSAPP_CLOUD_VERIFY_TOKEN` (Bref : `WHATSAPP_VERIFY_TOKEN`) ; signature `X-Hub-Signature-256` vérifiée avec `KEEVO_WHATSAPP_CLOUD_APP_SECRET` = secret de **l'app Meta qui reçoit le webhook** (Bref : `WHATSAPP_APP_SECRET`) ; `KEEVO_WHATSAPP_CLOUD_GRAPH=meta`. Garder les mêmes champs abonnés que Bref (au moins `messages`). |

Côté Keevo, ces chemins sont déjà publics (`SecurityConfig.PUBLIC_PATHS`) et documentés dans
`keevo/backend/src/main/resources/application.yml` (blocs `whatsapp-cloud`, `payments`).

### 2.2 Point à vérifier pour Meta avant J0

Le webhook Meta est **un par app Meta**. Le numéro plateforme Bref est repris par Keevo (les clés
`KEEVO_WHATSAPP_CLOUD_PLATFORM_*` retombent sur `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_BUSINESS_ACCOUNT_ID`,
`WHATSAPP_ACCESS_TOKEN`) et les comptes WhatsApp Business des owners sont importés par 24.6. Si ces comptes sont
reliés à l'app Meta de Bref, Keevo doit utiliser **cette** app (`KEEVO_WHATSAPP_CLOUD_APP_ID` / `APP_SECRET` = ceux de
Bref) et c'est son webhook qu'on repointe ; sinon les messages entrants des owners n'arriveraient pas. À confirmer
dans Meta for Developers (app propriétaire de chaque WABA) pendant la répétition 24.12.

### 2.3 Moment

Pendant la coupure (Bref arrêté, avant le « go ») : les événements émis pendant la coupure sont refusés ou réessayés
par les fournisseurs (RevenueCat et Meta réessaient ; MyCoolPay est rattrapé par la vérification de statut du paiement
côté Keevo). Aucun routeur ne livre aux deux backends.

### 2.4 [UTILISATEUR] Un événement de test par fournisseur

- **RevenueCat** : bouton *Send test event* du webhook → `docker logs keevo_backend | grep -i revenuecat` ; réponse
  200 `{"status":"ok"}` dans l'historique des envois RevenueCat (401 = secret différent).
- **MyCoolPay** : un paiement réel du plus petit montant (ou l'outil de test de l'espace marchand) → le paiement passe
  « confirmé » dans Keevo ; réponse `OK` dans l'historique MyCoolPay (`KO` = hash ou signature).
- **Meta** : « Vérifier et enregistrer » doit réussir (GET de vérification), puis *Test* sur le champ `messages` ou un
  message envoyé au numéro plateforme → visible dans la boîte super-admin (21.6). 403 = jeton de vérification ou
  secret d'app différent.

### 2.5 Retour arrière (avant le « go » uniquement)

Remettre les trois URL Bref du tableau 2.1 (mêmes secrets), puis rallumer api-bref et vérifier un événement de test
par fournisseur.

## 3. Commande de bascule (story 24.9)

> Écrit par 24.9 (outil livré), complété par 24.13 (retour arrière d'un seul owner). L'enchaînement de la journée est au
> §6 ; les commandes exactes de restauration, de rôle et de réglage sont aux §6.4–6.7.

### 3.1 [UTILISATEUR] Préparer la copie restaurée

1. Restaurer le dump de Bref (pris **après** l'arrêt d'api-bref) dans une base à part, `bref_bascule` (commandes
   exactes : §6.2–6.4).
2. En tant que propriétaire de cette base, créer les index d'aide (jamais sur Bref lui-même) :
   `psql -d bref_bascule -f keevo/ops/migration/bref-copy-indexes.sql` (idempotent, 11 index, puis `ANALYZE`).
   Sans eux chaque owner parcourt les tables Bref non indexées (coût O(owners × table)) ; le rapport affiche
   « index de la copie : n / 11 ».
3. Créer un rôle en lecture seule (`GRANT SELECT` sur le schéma `public`, `default_transaction_read_only = on`, §6.6) et
   le donner au backend (§6.7) : `KEEVO_MIGRATION_BREF_JDBC_URL`, `…_USERNAME`, `…_PASSWORD` (optionnel :
   `keevo.migration.bref.jdbc.pool-size`, 8 par défaut — jamais en dessous du parallélisme choisi).
4. Parallélisme : chaque owner en cours tient une connexion de la base Keevo ; garder au moins 2 connexions libres
   (`spring.datasource.hikari.maximum-pool-size` ≥ parallélisme + 2 ; 10 par défaut → parallélisme 4 conseillé).
   Au parallélisme 4, des créations d'entreprises simultanées ont rencontré des interblocages PostgreSQL dans les
   scripts de modules (repris automatiquement par l'étape « modules » de 24.6, réconciliation restée verte) : 24.12
   mesure 4 puis 8 sur la copie de prod avant de fixer la valeur.

### 3.2 Lancer, suivre, décider (API super-admin, écran « Bascule » du tableau de bord)

| Étape | Appel | Réponse |
|---|---|---|
| Lancer | `POST /api/v1/admin/migration/cutovers` `{"source":"DATABASE","parallelism":4}` | 202, la bascule tourne en arrière-plan |
| Suivre | `GET /api/v1/admin/migration/cutovers/{id}` (toutes les 2–5 s) | `status`, `progress` (owners par statut), `report` |
| Détail | `GET …/cutovers/{id}/owners?status=GAP&after=<position>` | owners « Compte n », motif français |
| Relancer | `POST …/cutovers/{id}/runs` | owners non réconciliés et owners ajoutés depuis |
| « go » | `POST …/cutovers/{id}/activation` `{"confirmation":"ACTIVER","acceptedOwners":[…]}` | 200, ou 409 avec `details.blockingOwners` |
| Retour arrière | `POST …/cutovers/{id}/rollback` `{"confirmation":"RETOUR ARRIERE"}` | 202, avant le « go » seulement |
| Retour arrière d'un owner (24.13) | `POST …/cutovers/{id}/owners/{ownerId}/rollback` `{"confirmation":"RETOUR ARRIERE"}` | 200 : son entreprise retirée, sa ligne « En attente », bascule « Interrompue : relancez-la » ; puis « Relancer » le réimporte (§7). Avant le « go » seulement ; 409 si la bascule tourne, est décidée, a commencé son retour arrière global, ou si la ligne n'a rien à retirer |
| Reprise d'un owner après le « go » (24.9, 11/11/2026) | `POST …/cutovers/{id}/owners/{ownerId}/recovery` `{"confirmation":"REPRENDRE"}` | 202 : bascule « Reprise d'un compte en cours », l'owner est réimporté depuis la copie figée, réconcilié, et sa seule entreprise activée ; puis la bascule revient « Activée » (§8.3). 200 sans rien faire si l'owner est déjà « Activé ». Après le « go » seulement ; 409 avant le « go » (relancer la bascule), pendant une autre reprise, ou pour un owner « Non repris » ou « Supprimé par le retour arrière » |

- **Owners acceptés** : un owner en écart, bloqué ou en échec ne passe le « go » que s'il figure dans
  `acceptedOwners` avec un motif (10 caractères au moins, l'écart documenté par la répétition 24.12). Il est activé si
  son import est complet ; sinon il reste hors de la bascule, puis se reprend seul après le « go » une fois sa cause
  corrigée (§8.3).
- Le « go » n'envoie **aucun message** : l'annonce « Bref Point est à jour » part par la campagne plateforme (21.6),
  §24.13. Les invitations des membres déjà inscrits dans Keevo sont redatées (7 jours à partir du « go »).
- Après le « go », plus de retour arrière automatique.
- **Redémarrage du backend pendant la bascule** : la bascule reste « En cours » ; elle ne peut être relancée (ou
  annulée) que 30 minutes après le dernier signe de vie de l'ancien coordinateur (protection contre deux coordinateurs
  en parallèle). Un owner interrompu en plein import peut revenir « Import en échec » avec « Un import de ce compte
  était déjà en cours » : le relancer une fois l'heure d'import périmée passée (1 h, 24.2).
- Une bascule dont le retour arrière a commencé ne se relance plus : terminer le retour arrière, puis en ouvrir une
  nouvelle.

### 3.3 Retour arrière (avant le « go »)

`POST …/rollback` supprime chaque entreprise importée (schéma, adhésions, comptes **créés** par l'import, identités
Google/Apple et abonnements navigateur ajoutés, lignes de facturation importées, traces de migration). Les comptes Keevo
qui existaient avant l'import (owner ou membre rattaché) sont conservés avec leur propre entreprise. Aucun message
« compte supprimé » n'est envoyé. Puis défaire §1 et §2 et rallumer api-bref (rien n'y a été modifié).

### 3.4 Mesures de 24.9 (base de travail de 24.12)

Copie du dump local du 03/11 + jeu synthétique `keevo/ops/migration/bref-synthetic-load.sql` (201 owners synthétiques
dont un de 20 000 documents, + l'owner réel) : 202 owners, 223 boutiques, 25 006 documents, 50 048 lignes, 19 414
encaissements ; parallélisme 4, poste de développement (base et backend sur la même machine).

| Essai | Index de la copie | Durée de la commande | Moyenne / owner | p95 | Owner le plus lourd | Retour arrière |
|---|---|---|---|---|---|---|
| 1 (sans étapes 24.6) | 0 / 10 | 392,6 s | 7,7 s | 13,8 s | 27,6 s | 20,6 s |
| 2 (avec étapes 24.6) | 11 / 11 | 343,3 s | 6,8 s | 8,6 s | 24,7 s | 33,9 s |

L'essai 1 tournait avant l'arrivée de 24.6 : son script d'index n'en comptait que 10 (l'index
`ai_conversation("userId")` a été ajouté avec les étapes 24.6) ; sur un schéma Bref ancien sans certaines tables, le
total attendu baisse d'autant (seules les tables présentes comptent).

Contrôle de 24.13 (11/11/2026, poste de développement, copie synthétique de 21 owners dont un de 300 documents, index
11 / 11, parallélisme 4, runbook joué tel qu'écrit : §0.4, §6.6, §6.7, §6.8, §6.10, §7, §6.14, §11.6) : commande 48,8 s
(moyenne 8,7 s / owner, petit lot : le coût fixe domine) ; écart provoqué sur un owner → « go » refusé (1 bloquant) ;
retour arrière de cet owner seul 0,3 s ; relance (lui seul) 4,1 s → réconcilié ; « go » 0,1 s ; 3 campagnes « c'est
fait » créées en brouillon par lots de 10 puis supprimées, aucun envoi.

Lecture : le coût est surtout **fixe par owner** (création de l'entreprise et de son schéma, provisionnement des
modules : ≈ 6,5 s), le volume de documents ajoute ≈ 1 s par 1 000 documents ; la réconciliation pèse ≈ 1 % (≈ 90 ms
par owner). Extrapolation à vérifier par 24.12 : durée ≈ owners × 6,8 s ÷ parallélisme + owner le plus lourd, soit
pour 1 000 owners ≈ 29 min au parallélisme 4 (≈ 15–20 min au parallélisme 8 si la base de prod le supporte). 24.12
mesure sur la copie de prod (nombre réel d'owners, parallélisme 4 puis 8) et fixe la fenêtre de coupure annoncée.

## 4. Préparation (J-10 → J-7)

### 4.1 [UTILISATEUR] Modèles WhatsApp de la bascule (J-10 au plus tard)

Soumettre dans Meta les 4 modèles `bref_bascule_annonce`, `bref_bascule_rappel`, `bref_bascule_terminee`,
`bref_bascule_incident`, chacun en **Français** et en **Anglais**, sur le compte WhatsApp Business du **numéro
plateforme** (celui de Bref, repris par Keevo) : texte exact, exemples et pied de page dans
`keevo/backend/docs/whatsapp/modeles-meta-plateforme.md` §5. Catégorie Utilitaire ; l'approbation prend de quelques
minutes à 48 h. Ne **pas** les ajouter à `WHATSAPP_APPROVED_TEMPLATES`. Si Meta refuse ou reclasse un modèle, corriger
le texte dans `PlatformTemplateCatalog`, regénérer le document et resoumettre ; en attendant, le canal WhatsApp de la
campagne est retiré (SMS, notification et e-mail suffisent).

Contrôle : dans WhatsApp Manager › Modèles de messages, les 8 lignes (4 noms × 2 langues) sont « Approuvé ».

### 4.2 [UTILISATEUR] Continuité des comptes Apple et Google (story 24.7)

L'identifiant Apple d'un owner (`sub`) repris par 24.7 n'est valable que pour **la même équipe Apple** (Team ID).

1. App Store Connect › l'app Bref Point › Informations de l'app : relever l'**identifiant de bundle**
   (`com.brefpoint.app` attendu) et l'**équipe** (Team ID, 10 caractères). La nouvelle app React Native doit être
   publiée comme **mise à jour de cette même fiche** (même bundle, même équipe) : c'est ce qui garde les `sub` Apple.
2. Sur le serveur Keevo : `grep -E '^APPLE_BUNDLE_IDS=|^APPLE_BUNDLE_ID=' ~/keevo/.env` doit contenir le bundle de
   Bref Point (liste séparée par des virgules ; sans lui, Keevo refuse les jetons Apple de l'app). Si
   `APPLE_REVOCATION_ENABLED=true`, `APPLE_TEAM_ID` est celui relevé en 1.
3. `grep -E '^GOOGLE_CLIENT_IDS=' ~/keevo/.env` contient les identifiants client OAuth Google de Bref Point (Android,
   iOS, web — Google Cloud Console › Identifiants du projet de Bref).
4. **Si l'équipe ou le bundle diffèrent** (app publiée sous un autre compte) : les owners Apple ne retrouveront pas leur
   compte par Apple. Prévenir par le message « c'est fait » (variante §11.3) : se connecter avec le **téléphone et un
   code** (ou identifiant + mot de passe Bref), puis relier Apple depuis le profil (19.3b). Noter la décision au §14.

### 4.3 [UTILISATEUR] App React Native prête (story 23.12)

- Build de production pointé sur `https://keevo.ad2s.cm`, validé sur staging (22.17 / 22.18).
- Soumise aux deux stores **à J-7 au plus tard** avec **publication manuelle** (App Store : « Publier manuellement
  cette version » ; Google Play : « Publication gérée » / envoi en examen sans déploiement automatique), pour qu'elle
  soit approuvée **avant** J0 et publiée d'un clic après le « go » (§6.16).
- Rappel : l'ancienne app Bref Point parle à api-bref, qui sera arrêté ; elle affichera des erreurs réseau jusqu'à la
  mise à jour. C'est pourquoi le message « c'est fait » ne part qu'une fois la nouvelle version visible dans les stores.

### 4.4 Répétition générale (24.12) terminée

24.12 joue ce runbook sur une copie de la prod Bref (dump fourni par l'utilisateur) et remplit la fiche §14 : durée de
chaque étape, durée totale, parallélisme retenu (4 ou 8), owners en écart ou bloqués avec leur cause. Sans cette fiche,
**pas de date annoncée**. À partir d'elle, fixer :

- `<DATE_ARRET>` (jour et heure de l'arrêt) et `<DATE_RETOUR>` (heure limite de retour, §0.2) ;
- l'heure limite de décision ;
- la liste des owners à traiter avant J0 (§4.5) et des écarts déjà documentés (motifs d'acceptation possibles §6.13).

### 4.5 Cas bloquants d'identité à résoudre avant J0 (story 24.7)

La répétition liste les owners « Import bloqué » et leurs cas (`GET …/batches/{batchId}` → `cases[].label`). Bref tourne
encore : on corrige **à la source** (dans Bref, par l'owner) ou **côté Keevo** (par le titulaire du compte Keevo), et la
copie de J0 portera la correction. Jamais d'écriture directe en base.

| Cas (libellé affiché) | Cause | Résolution |
|---|---|---|
| Rapprochement impossible : plusieurs comptes Keevo correspondent | le téléphone mène à un compte Keevo, l'e-mail à un autre | l'owner choisit son compte ; il retire l'e-mail de l'autre compte Keevo (profil) ou change l'e-mail de son compte Bref |
| Un compte Keevo correspond, mais l'identité n'est pas prouvée dans Bref | téléphone non confirmé dans Bref | l'owner confirme son numéro dans Bref (code reçu) avant l'arrêt ; si le compte Keevo n'est pas le sien, le support l'aide à changer le téléphone de son compte Bref |
| Identifiant de connexion Bref déjà utilisé par un autre compte Keevo | identifiant pris | l'un des deux change d'identifiant (profil Bref ou profil Keevo) |
| Identifiant de connexion Bref égal au téléphone d'un autre compte Keevo | identifiant = numéro d'un autre | l'owner change d'identifiant dans Bref |
| Compte Google ou Apple déjà relié à un autre compte Keevo | identité sociale prise | le titulaire du compte Keevo la délie depuis son profil (19.3b), ou l'owner se connectera par téléphone et la reliera ensuite |
| Le compte Keevo correspondant est désactivé ou réservé à l'administration | compte cible inutilisable | réactiver le compte Keevo (support) s'il est à l'owner ; sinon l'owner change de téléphone dans Bref |

Un cas non résolu à J0 se traite au §6.11.

### 4.6 J-7 : annonce

- Envoyer l'**annonce** (§11.1) par les **campagnes de l'admin Bref** (les owners n'existent encore que dans Bref) :
  une campagne par canal (WhatsApp, SMS, notification, e-mail), cible : comptes actifs, rôles owner, admin et seller
  (les vendeurs aussi ont des ventes hors connexion). Corps prêts au §11.5.
- Version anglaise : envoyée en plus aux comptes anglophones connus du support (liste explicite, §11.5) ; par défaut la
  version française part à tous.
- **Risque accepté** (FUS-MIG6, décision de l'owner produit du 11/11/2026) : une vente enregistrée hors connexion dans
  l'ancienne app et jamais envoyée avant l'arrêt est perdue. L'annonce, le rappel et la consigne « notez sur papier
  pendant l'arrêt » sont la seule parade.

## 5. Veille (J-2, J-1)

### 5.1 J-2

- Option B du §1.3 seulement : TTL de l'enregistrement DNS de l'hôte Bref baissé à 300 s.
- Gel des déploiements Keevo (backend) et api-bref jusqu'à J+1 (sauf correctif décidé au §7).
- Sauvegarde Keevo à jour (procédure habituelle `backup-db.sh`, `docs/MOP_MISE_A_JOUR_PROD.md`).

### 5.2 J-1

1. **Rappel** (§11.2) par les campagnes de l'admin Bref, mêmes cibles qu'au §4.6.
2. Serveur Keevo, réglages présents (sans afficher les valeurs) :
   ```bash
   cd ~/keevo && for k in KEEVO_PUBLIC_BASE_URL KEEVO_SECRETS_ENCRYPTION_KEY REVENUECAT_WEBHOOK_SECRET \
     MYCOOLPAY_PUBLIC_KEY MYCOOLPAY_PRIVATE_KEY MYCOOLPAY_CALLBACK_HASH KEEVO_WHATSAPP_CLOUD_VERIFY_TOKEN \
     KEEVO_WHATSAPP_CLOUD_APP_SECRET APPLE_BUNDLE_IDS GOOGLE_CLIENT_IDS; do
     grep -q "^$k=." .env && echo "$k présent" || echo "$k MANQUANT"; done
   ```
   Attendu : tout « présent ». `KEEVO_SECRETS_ENCRYPTION_KEY` manquant = les WhatsApp Business des owners seraient
   bloqués à l'import (cas « Compte WhatsApp Business non repris… »). Si `APPLE_REVOCATION_ENABLED=true` : vérifier
   aussi `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY` (§4.2).
   Le service `backend` de `~/keevo/docker-compose.prod.yml` doit lire `env_file: .env` (c'est le cas du fichier généré
   par le déploiement) et joindre la base sous le nom `postgres` :
   `grep -A12 '^  backend:' ~/keevo/docker-compose.prod.yml | grep -E 'env_file|POSTGRES_HOST'` → `env_file:` et
   `POSTGRES_HOST: postgres`. Sinon adapter le nom d'hôte de l'URL du §6.7.
   Versions : `docker exec bref_postgres postgres --version` (serveur Bref) ≤ `docker exec keevo_postgres postgres
   --version` (serveur Keevo) en version majeure (16 attendu des deux côtés) ; sinon `pg_restore` refuse le dump.
3. Espace disque : `df -h` sur les deux serveurs ≥ 3 × la taille de la base Bref (dump + restauration + marge).
4. Le compte super-admin Keevo se connecte (§0.4) et `GET $API/api/v1/admin/migration/cutovers` répond 200 ; aucune
   bascule ouverte (`status` RUNNING / READY / FAILED) ne traîne de la répétition.
5. L'app React Native est « Prête pour la vente / en attente de publication » dans les deux stores (§4.3).
6. Les 8 modèles WhatsApp sont approuvés (§4.1).
7. La personne de chaque rôle (§0.3) est disponible pendant toute la fenêtre ; le §12 est rempli.
8. Serveur Keevo : `mkdir -m 700 -p ~/keevo/bascule`, y déposer `keevo/ops/migration/bref-copy-indexes.sql` (version
   du commit déployé) ; `jq` et `split` installés sur le poste du super-admin et le serveur.

## 6. J0 pas à pas

Noter l'heure de début et de fin de chaque étape dans la fiche §14.

### 6.1 T0 — arrêt d'api-bref (≈ 1 min)

Sur le serveur de Bref (`<serveur Bref>`, dossier du `docker-compose.prod.yml` d'api-bref) :

```bash
docker stop api-bref          # l'API seule ; la base bref_postgres reste allumée pour le dump
docker ps --filter name=api-bref --format '{{.Status}}'   # attendu : rien (conteneur arrêté)
curl -s -o /dev/null -w "%{http_code}\n" "https://<hôte Bref>/api/v1/invoices/public/count?identifier=x"
# attendu : 502 / 503 / 404 du proxy (plus de réponse d'api-bref)
```

À partir de là plus aucune écriture n'arrive dans Bref. **Ne pas** supprimer le conteneur ni le volume
`bref_postgres_data` : c'est le retour arrière.

### 6.2 Dump (2–10 min)

```bash
umask 077
set -a; . ./.env; set +a      # DATABASE_USERNAME, DATABASE_NAME d'api-bref (rien n'est affiché)
docker exec bref_postgres pg_dump -U "$DATABASE_USERNAME" -d "$DATABASE_NAME" -Fc -Z 6 > bref-bascule-<AAAAMMJJ>.dump
ls -l bref-bascule-<AAAAMMJJ>.dump && sha256sum bref-bascule-<AAAAMMJJ>.dump > bref-bascule-<AAAAMMJJ>.dump.sha256
```

Attendu : code retour 0, fichier non vide, droits `-rw-------`. Ce dump est **figé** : c'est la seule source de la
bascule (jamais une base Bref en marche : ses nouvelles lignes apparaîtraient comme des écarts).

### 6.3 Transfert vers le serveur Keevo (1–5 min)

```bash
scp -p bref-bascule-<AAAAMMJJ>.dump bref-bascule-<AAAAMMJJ>.dump.sha256 <utilisateur>@<serveur Keevo>:~/keevo/bascule/
# sur le serveur Keevo :
cd ~/keevo/bascule && chmod 600 bref-bascule-* && sha256sum -c bref-bascule-<AAAAMMJJ>.dump.sha256   # attendu : OK
```

(Créer `~/keevo/bascule` en `chmod 700` à J-1.)

### 6.4 Restauration dans une base à part (5–15 min avec 6.5)

Sur le serveur Keevo, dans le PostgreSQL de Keevo (même version 16 que Bref) :

```bash
cd ~/keevo && set -a; . ./.env; set +a     # POSTGRES_USER, POSTGRES_DB
# nouvelle tentative (copie gardée d'un essai précédent, §8.1) : repartir d'une base vide
docker exec keevo_postgres psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE IF EXISTS bref_bascule"
docker exec keevo_postgres createdb -U "$POSTGRES_USER" bref_bascule
docker exec -i keevo_postgres pg_restore -U "$POSTGRES_USER" -d bref_bascule --no-owner --no-acl \
  < bascule/bref-bascule-<AAAAMMJJ>.dump
docker exec keevo_postgres psql -U "$POSTGRES_USER" -d bref_bascule -Atc \
  "SELECT count(*) FROM \"user\" WHERE role = 'owner'"     # = N, comparer à la fiche 24.12
```

Attendu : `pg_restore` sans erreur (un avertissement sur une extension déjà présente est sans effet) ; `N` cohérent avec
la répétition.

### 6.5 Index d'aide sur la copie

```bash
docker exec -i keevo_postgres psql -U "$POSTGRES_USER" -d bref_bascule -v ON_ERROR_STOP=1 \
  < bascule/bref-copy-indexes.sql      # copie de keevo/ops/migration/bref-copy-indexes.sql, déposée à J-1
```

Attendu : `CREATE INDEX` × n puis `ANALYZE`. Le rapport de la bascule affichera « index de la copie : 11 / 11 » (moins
si le schéma Bref n'a pas certaines tables).

### 6.6 Rôle en lecture seule pour l'outil

Mot de passe généré sur place, jamais écrit ailleurs que dans `~/keevo/.env` :

```bash
BREF_RO_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=')
docker exec -i keevo_postgres psql -U "$POSTGRES_USER" -d bref_bascule -v ON_ERROR_STOP=1 -v pw="$BREF_RO_PASSWORD" <<'SQL'
DROP ROLE IF EXISTS keevo_bref_ro;   -- essai précédent : son mot de passe n'est plus connu
CREATE ROLE keevo_bref_ro LOGIN PASSWORD :'pw';
ALTER ROLE keevo_bref_ro SET default_transaction_read_only = on;
REVOKE ALL ON DATABASE bref_bascule FROM PUBLIC;
GRANT CONNECT ON DATABASE bref_bascule TO keevo_bref_ro;
GRANT USAGE ON SCHEMA public TO keevo_bref_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO keevo_bref_ro;
SQL
```

Contrôle : `docker exec keevo_postgres psql "postgresql://keevo_bref_ro:$BREF_RO_PASSWORD@localhost/bref_bascule" -Atc
"CREATE TABLE x(i int)"` doit échouer (« cannot execute CREATE TABLE in a read-only transaction »).

### 6.7 Brancher le backend sur la copie (5–10 min)

Ajouter dans `~/keevo/.env` (variables lues par `env_file`) puis recréer le seul conteneur du backend :

```bash
sed -i '/^# Bascule Bref (24.13)/d;/^KEEVO_MIGRATION_BREF_/d;/^SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=/d' ~/keevo/.env
cat >> ~/keevo/.env <<EOF
# Bascule Bref (24.13) — à retirer à J+7 (§10)
KEEVO_MIGRATION_BREF_JDBC_URL=jdbc:postgresql://postgres:5432/bref_bascule
KEEVO_MIGRATION_BREF_JDBC_USERNAME=keevo_bref_ro
KEEVO_MIGRATION_BREF_JDBC_PASSWORD=$BREF_RO_PASSWORD
KEEVO_MIGRATION_BREF_JDBC_POOLSIZE=8
KEEVO_MIGRATION_BREF_LOGO_FETCHENABLED=true
SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=12
EOF
unset BREF_RO_PASSWORD
cd ~/keevo && docker compose -f docker-compose.prod.yml up -d backend
docker logs --tail 50 keevo_backend | grep -E "Started|ERROR"     # attendu : « Started … » sans ERROR
```

`POOLSIZE` ≥ parallélisme ; `MAXIMUMPOOLSIZE` ≥ parallélisme + 2 (valeurs pour le parallélisme 4 ou 8 retenu par 24.12).
Les logos de boutique sont rapatriés depuis Cloudinary (`LOGO_FETCHENABLED`).

### 6.8 Commande de bascule (`N × 6,8 s ÷ parallélisme` + ≤ 30 s)

```bash
# (poste)
P=<4 ou 8, fiche §14>
R=$(curl -s -X POST "$API/api/v1/admin/migration/cutovers" "${H[@]}" -d "{\"source\":\"DATABASE\",\"parallelism\":$P}")
CUTOVER=$(echo "$R" | jq -r '.data.id // empty')
[ -n "$CUTOVER" ] || { echo "Refus : $(echo "$R" | jq -r '.error')"; }   # ne pas continuer sans identifiant
echo "$CUTOVER"        # identifiant de travail, à garder pour toute la journée (noter au §14)
suivre() {   # suivi toutes les 10 s jusqu'à un état stable ; un 401 (jeton expiré) relance la connexion
  while :; do
    R=$(curl -s -w '\n%{http_code}' "$API/api/v1/admin/migration/cutovers/$CUTOVER" "${H[@]}")
    [ "$(echo "$R" | tail -1)" = 401 ] && { login || return 1; continue; }
    echo "$R" | sed '$d' | jq -c '.data | {status: .statusLabel, progress: [.progress[] | {(.label): .count}], erreur: .errorLabel}'
    case "$(echo "$R" | sed '$d' | jq -r '.data.status')" in READY|FAILED|ACTIVATED|ROLLED_BACK) return 0;; esac
    sleep 10
  done
}
suivre
```

Attendu : 202 au lancement, puis `statusLabel` « En cours » → « Prête pour la décision « go » ». Un 409 « Une bascule
est déjà ouverte… » : une bascule de la répétition traîne (la clore par son retour arrière d'abord). « Base Bref
restaurée inaccessible » : revoir 6.6 / 6.7 puis `POST …/cutovers/$CUTOVER/runs`.

Même écran dans le tableau de bord super-admin : « Bascule » (d24-9).

### 6.9 Pendant la commande : webhooks repointés (≈ 15 min)

En parallèle de 6.8 (Bref est arrêté) : appliquer le §2 (RevenueCat, MyCoolPay, Meta), puis les tests du §2.4. Noter
l'heure de chaque changement au §14 (ce sont les gestes à défaire au §8).

### 6.10 Lire le rapport (READY)

```bash
curl -s "$API/api/v1/admin/migration/cutovers/$CUTOVER" "${H[@]}" | jq '.data.report'
# owners à traiter, par statut (GAP = écart, BLOCKED = import bloqué, FAILED = import en échec)
for S in GAP BLOCKED FAILED; do
  curl -s "$API/api/v1/admin/migration/cutovers/$CUTOVER/owners?status=$S&limit=100" "${H[@]}" \
    | jq -r --arg s "$S" '.data[] | [$s, .position, .label, (.companyName // "-"), .reason, .brefOwnerId, (.batchId // "-")] | @tsv'
done
```

Le rapport donne : owners par statut et par motif, comptes et sommes Bref / Keevo (ventes, encaissé, restant dû,
dépenses), anomalies Bref conservées et notées, index de la copie, durées. Au-delà de 100 lignes, page suivante avec
`&after=<position de la dernière ligne>` (2e colonne). Durée : 5–10 min de lecture ; attendu : la liste des owners à
traiter (souvent vide) et des sommes Bref = Keevo.

### 6.11 Owner « Import bloqué » (cas d'identité non résolu) — 10–30 min par owner

Attendu à la fin : chaque owner bloqué est soit « Importé et réconcilié » après relance, soit inscrit au §14 comme
accepté hors bascule (motif, owner prévenu).

1. Lire les cas : `curl -s "$API/api/v1/admin/migration/batches/<batchId>" "${H[@]}" | jq '.data.cases[].label'`.
2. Si la correction se fait **côté Keevo** pendant la fenêtre (le titulaire du compte Keevo délie son Google / Apple,
   change d'identifiant ou retire un e-mail, §4.5) : relancer — `POST $API/api/v1/admin/migration/cutovers/$CUTOVER/runs`
   (202) ; seuls les owners non réconciliés repassent ; attendre READY.
3. Si elle demande une correction **dans Bref** : impossible pendant la coupure (Bref arrêté, copie figée). Choix du
   responsable : soit l'owner reste hors de la bascule (accepté avec motif au « go », §6.13 ; son entreprise n'est pas
   créée, il est prévenu personnellement), soit « no-go » global si l'owner est critique.

### 6.12 Owner « Écart de réconciliation » — 10 min (accepté) à 1 h (correctif de l'outil)

Procédure complète au §7 (lire le rapport de l'owner, décider, retour arrière de cet owner seul, réimport).

### 6.13 Décision « go » / « no-go » (critères écrits)

**« go » seulement si TOUS les points sont vrais** (le responsable les coche au §14) :

1. Bascule « Prête pour la décision « go » » ; aucun owner « En attente ».
2. Aucun owner « Écart de réconciliation », **sauf** un écart dont la même cause est documentée par la répétition 24.12
   (motif repris mot pour mot dans `acceptedOwners`).
3. Owners « Import bloqué » ou « Import en échec » restants : chacun décidé (hors bascule, motif d'au moins 10
   caractères) et **au plus 1 % des owners**, aucun parmi les 20 plus gros owners en documents ; chacun sera contacté
   personnellement par le support. Accepter un owner hors bascule veut dire qu'il **n'a pas de compte Keevo au
   « go »** : il est repris seul après le « go », depuis la même copie figée, une fois sa cause corrigée (§8.3).
4. Rapport : sommes Bref = sommes Keevo pour les owners réconciliés (ventes, encaissé, restant dû, dépenses) ; index de
   la copie complets.
5. Webhooks repointés et un événement de test réussi par fournisseur (§2.4).
6. L'app React Native est approuvée dans les deux stores, prête à publier (§4.3).
7. L'heure limite de décision (§0.2) n'est pas dépassée.

**« no-go »** dès qu'un point est faux et ne peut pas être rendu vrai avant l'heure limite : retour arrière (§8),
message d'incident (§11.4) une fois Bref rallumé.

### 6.14 « go » : activation globale (< 1 min)

```bash
curl -s -X POST "$API/api/v1/admin/migration/cutovers/$CUTOVER/activation" "${H[@]}" -d '{
  "confirmation": "ACTIVER",
  "acceptedOwners": [
    {"ownerId": "<brefOwnerId>", "reason": "<motif : écart documenté par 24.12 / cas d identité non résolu, owner prévenu>"}
  ]}' | jq '.data | {status: .statusLabel, progress}'
```

Attendu : 200, « Activée (« go » donné) », owners « Activé ». Un 409 renvoie `details.blockingOwners` (owner,
« Compte n », motif) : les traiter (§6.11, §7) ou les ajouter à `acceptedOwners` selon §6.13. `acceptedOwners: []` s'il
n'y a rien à accepter. Aucun message n'est envoyé par le « go ». **À partir d'ici, rallumer Bref perdrait les écritures
faites sur Keevo** : plus de retour arrière automatique (§8.3).

### 6.15 Redirection des anciens liens publics (10–15 min)

§1.4 puis contrôles §1.5 (5 jetons réels : 302 → 200).

### 6.16 [UTILISATEUR] Publier l'app React Native — 5 min, puis propagation (minutes à quelques heures)

App Store Connect : « Publier cette version » ; Google Play : publier la version approuvée (déploiement à 100 %).
Contrôle : la nouvelle version est visible dans les deux stores sur un téléphone de l'équipe (la propagation peut prendre
de quelques minutes à quelques heures, surtout sur Google Play). Connexion de contrôle avec un compte de l'équipe importé.

### 6.17 Message « c'est fait » — 15–30 min

Dès que l'app est visible dans les stores : §11.3 par les **campagnes plateforme Keevo** (21.6), ciblées sur les
entreprises activées par la bascule (§11.6, lots de 100). Créer en brouillon, vérifier l'aperçu, envoyer. Attendu : une
campagne « Envoyée » par lot, compteurs d'envoi par canal dans le tableau de bord.

### 6.18 Contrôles immédiats (30 min)

- (poste) `GET $API/api/v1/admin/migration/cutovers/$CUTOVER` : « Activée » ; (serveur Keevo) aucune entreprise restée
  suspendue par l'import :
  ```bash
  docker exec keevo_postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
    "SELECT count(*) FROM public.tenants WHERE status = 'SUSPENDED' AND suspended_reason LIKE 'Migration Bref%'"
  ```
  attendu : 0, ou le nombre d'owners acceptés « Import en échec » dont l'entreprise avait été créée (elle reste
  suspendue, hors bascule, jusqu'à sa reprise §8.3).
- 3 owners tirés au hasard : connexion dans la nouvelle app (avec leur accord, ou comptes de l'équipe), boutiques,
  dernières factures et abonnement visibles.
- `docker logs --since 1h keevo_backend | grep -c ERROR` comparable à un jour normal.
- Webhooks : un vrai événement de chaque fournisseur reçu dans l'heure (paiement, renouvellement, message entrant).

## 7. Owner en écart de réconciliation (`RECONCILIATION_FAILED`, story 24.8)

Un owner « Écart de réconciliation » bloque le « go » tant qu'il n'est pas corrigé ou accepté. La source reste **le dump
figé** de 6.2 : jamais une base Bref en marche.

1. **Lire son rapport** (contrôles et lignes en écart, libellés français) :
   ```bash
   curl -s "$API/api/v1/admin/migration/bref/owners/<brefOwnerId>/reconciliation" "${H[@]}" | jq '.data |
     {statut: .statusLabel, ecarts: .gaps, expliques: .explained, erreur: .errorLabel,
      controles: [.checks[] | select(.status == "GAP") | {controle: .label, tolerance: .toleranceLabel,
        lignes: [.lines[] | select(.status == "GAP") | {ligne: .label, attendu: .expected, obtenu: .actual,
                                                        ecart: .difference}]}]}'
   ```
2. **Décider la cause** :
   - *donnée Bref incohérente déjà vue par 24.12* (même contrôle, même cause dans la fiche §14) → accepter au « go » avec
     ce motif (§6.13 point 2) ; fin.
   - *réconciliation qui n'a pas pu lire* (`erreur` non vide, par exemple base de la copie momentanément
     inaccessible) → `POST $API/api/v1/admin/migration/cutovers/$CUTOVER/runs` : l'import, idempotent, ne change rien
     et la réconciliation est rejouée.
   - *défaut de l'outil d'import* → étape 3.
3. **Corriger l'outil** (si besoin) : correctif de code, tests, déploiement du backend de Keevo pendant la fenêtre
   (procédure de déploiement habituelle ; la bascule « Prête » survit au redémarrage). Si le correctif ne tient pas avant
   l'heure limite : accepter l'owner hors bascule (§6.13 point 3) ou « no-go ».
4. **Retour arrière de cet owner seul** (avant le « go » uniquement) :
   ```bash
   curl -s -X POST "$API/api/v1/admin/migration/cutovers/$CUTOVER/owners/<brefOwnerId>/rollback" "${H[@]}" \
     -d '{"confirmation":"RETOUR ARRIERE"}' | jq '.data | {status: .statusLabel, erreur: .errorLabel}'
   ```
   Attendu : 200, bascule « Interrompue : relancez-la » avec « Compte retiré et remis en attente : relancez la bascule
   pour le réimporter » ; la ligne de l'owner est « En attente ». Son entreprise importée, les comptes créés par l'import,
   ses identités Google / Apple et abonnements navigateur ajoutés et ses traces de migration sont supprimés ; un compte
   Keevo rattaché (qui existait avant) est conservé avec sa propre entreprise. Refus possibles (409, message français) :
   bascule en cours ou décidée, retour arrière global commencé, ligne sans rien à retirer, entreprise déjà active
   (jamais supprimée).
5. **Réimporter depuis la copie figée** : `POST $API/api/v1/admin/migration/cutovers/$CUTOVER/runs` (202) → attendre
   READY (≈ 7 s pour un owner moyen) → l'owner doit être « Importé et réconcilié ». Sinon recommencer à 1 ou décider
   selon §6.13.

## 8. Retour arrière

### 8.1 Avant le « go » (« no-go » ou incident)

| # | Geste | Commande / action | Durée |
|---|---|---|---|
| 1 | Retirer toutes les entreprises importées | `curl -s -X POST "$API/api/v1/admin/migration/cutovers/$CUTOVER/rollback" "${H[@]}" -d '{"confirmation":"RETOUR ARRIERE"}'` (202), puis suivi comme en 6.8 jusqu'à « Retour arrière effectué » (§3.3) ; « Retour arrière incomplet : n compte(s)… » → relancer le même appel | ≈ 0,2 s / owner |
| 2 | Webhooks remis sur Bref | §2.5 (mêmes secrets) | 10 min |
| 3 | Redirection des liens retirée (si posée) | §1.6 | 5 min |
| 4 | Rallumer api-bref | serveur Bref : `docker start api-bref`, puis `docker logs --tail 50 api-bref` (démarrage sans erreur) | 2 min |
| 5 | Contrôle Bref | connexion dans l'ancienne app Bref Point avec un compte de l'équipe ; un événement de test par fournisseur (§2.4, côté Bref) | 10 min |
| 6 | Débrancher la copie | retirer les lignes « Bascule Bref » de `~/keevo/.env`, `docker compose -f docker-compose.prod.yml up -d backend`, puis §10 étapes 3–5 (copie, rôle, dumps) — ou garder la copie si une nouvelle tentative est prévue sous 48 h | 10 min |
| 7 | Message d'incident | §11.4 variante « reportée », par les campagnes de l'admin Bref (Bref rallumé) | 10 min |

Les ventes faites pendant la coupure ne sont nulle part : les owners les ont notées (consigne du rappel).

### 8.2 Pendant la coupure, sans retour arrière

Si la fenêtre est tenue mais qu'un retard est probable, rien ne peut être envoyé aux owners Bref (Bref arrêté, owners
pas encore actifs dans Keevo) : le support répond aux messages reçus sur le numéro WhatsApp plateforme depuis la
messagerie super-admin (21.6, fenêtre de 24 h) et au téléphone (§12). D'où une heure de retour annoncée prudente.

### 8.3 Après le « go »

Plus de retour arrière automatique (`POST …/rollback` et `…/owners/{ownerId}/rollback` répondent 409) : rallumer Bref
ferait perdre ce qui a été écrit dans Keevo. Tout problème se corrige **sur Keevo** (support, correctif). Un incident
visible des owners : §11.4 variante « en cours », par les campagnes plateforme Keevo (§11.6). Un owner laissé hors
bascule au « go » reste dans la copie figée (gardée jusqu'à J+7) : le support le prévient personnellement, puis il est
repris **seul** dès que sa cause est corrigée (procédure ci-dessous). La copie figée est gardée tant que ces owners ne
sont pas repris (le nettoyage du §10 attend leur reprise).

**Reprise d'un owner laissé hors bascule** (story 24.9, reprise du 11/11/2026) — 5–15 min par owner :

1. Corriger la cause **sur Keevo** (jamais dans Bref : la source reste la copie figée) : cas d'identité (§4.5, §6.11 :
   le titulaire du compte Keevo libère l'identifiant, l'e-mail ou le Google / Apple), ou correctif de l'outil déployé
   (§7 étape 3). Une donnée Bref incohérente ne se corrige pas : l'owner reste hors bascule (pas d'acceptation d'écart
   après le « go »).
   Les owners laissés hors bascule et leur `brefOwnerId` (« Compte n » = `label`) :
   ```bash
   for S in BLOCKED FAILED GAP PENDING RECONCILED; do
     curl -s "$API/api/v1/admin/migration/cutovers/$CUTOVER/owners?status=$S" "${H[@]}" \
       | jq -r --arg s "$S" '.data[] | [$s, .label, (.companyName // "-"), (.reason // "-"), .brefOwnerId] | @tsv'
   done
   ```
2. Lancer la reprise de **cet owner seul** :
   ```bash
   curl -s -X POST "$API/api/v1/admin/migration/cutovers/$CUTOVER/owners/<brefOwnerId>/recovery" "${H[@]}" \
     -d '{"confirmation":"REPRENDRE"}' | jq '.data | {status: .statusLabel}'
   ```
   Attendu : 202, « Reprise d'un compte en cours ». Pendant la reprise, le « go », la relance et toute autre reprise
   répondent 409 « Une reprise de compte est en cours : attendez la fin » (une seule à la fois).
3. Suivre jusqu'au retour à « Activée » (≈ 7 s pour un owner moyen, quelques minutes pour un très gros) :
   ```bash
   curl -s "$API/api/v1/admin/migration/cutovers/$CUTOVER" "${H[@]}" | jq '.data | {status: .statusLabel,
     erreur: .errorLabel, repris: [.recoveredOwners[] | {compte: .label, le: .recoveredAt}],
     comptes: .report.recoveredOwners}'
   ```
   - `erreur` vide et l'owner dans `repris` : son import est réconcilié et son entreprise **seule** est active (les
     autres entreprises ne bougent pas), ses invitations sont redatées (7 jours). **Aucun message n'est envoyé** : le
     support le prévient lui-même (§11.3, envoi individuel).
   - `erreur` « Compte n repris mais non activé : <motif> » : l'import est encore bloqué, en échec ou en écart ; son
     entreprise reste suspendue (ou absente). Lire le motif (§6.11, §7 étape 1), corriger, recommencer à l'étape 2.
   - « La reprise du compte s'est arrêtée avant la fin : relancez-la » : recommencer à l'étape 2 (l'import est
     idempotent).
4. Refaire la même commande sur un owner déjà « Activé » répond 200 sans rien changer. Autres refus (409, message
   français) : avant le « go » (« Avant le « go », un compte se reprend en relançant la bascule », §7) ; bascule en
   cours, en retour arrière ou retour arrière effectué ; owner « Non repris (rien à migrer) » ou « Supprimé par le
   retour arrière » (« Ce compte n'a rien à reprendre… »). Un redémarrage du backend pendant une reprise laisse la
   bascule « Reprise d'un compte en cours » : 30 minutes après le dernier signe de vie, refaire l'étape 2 **pour le
   même owner** (la reprise interrompue est reprise, l'import est idempotent).
5. Noter dans la fiche §14 : owner (« Compte n »), heure de reprise, cause corrigée. Quand plus aucun owner n'attend :
   §10 point 3 (suppression de la copie) redevient possible.

## 9. J+1

1. Bascule toujours « Activée » ; nombre d'entreprises actives importées = owners « Activé » du rapport.
2. Erreurs du backend depuis le « go » : `docker logs --since 24h keevo_backend 2>&1 | grep -E "ERROR" | sort | uniq -c
   | sort -rn | head` — aucune erreur nouvelle liée aux comptes importés.
3. Webhooks : au moins un événement réel reçu et traité par fournisseur dans les dernières 24 h (RevenueCat,
   MyCoolPay, Meta) ; aucun 401 / 403 / `KO` dans leurs historiques d'envoi.
4. Anciens liens publics : 5 nouveaux jetons tirés au hasard (§1.5) → 302 → 200.
5. Support : liste des demandes reçues (connexion, données manquantes, ventes perdues) ; chaque « donnée manquante »
   est comparée au rapport de son owner (§7 étape 1) avant toute correction.
6. Stores : la nouvelle version est à 100 % sur les deux plateformes ; part des owners importés déjà connectés
   (tableau de bord super-admin, dernière connexion).

## 10. J+7 (jalon G4) et nettoyage

**Contrôles** : aucun écart de données signalé non expliqué ; webhooks et liens vérifiés (§9 points 3–4 rejoués) ;
le responsable déclare la bascule close (fiche §14). Ensuite seulement, **[UTILISATEUR]** :

1. Retirer les lignes « Bascule Bref (24.13) » de `~/keevo/.env` et recréer le backend
   (`docker compose -f docker-compose.prod.yml up -d backend`).
2. Vérifier que l'outil est débranché : `GET $API/api/v1/admin/migration/cutovers` répond toujours (lecture), un
   nouveau `POST …/cutovers` répond « source indisponible ».
3. Supprimer le rôle et la copie — **sauf** si un owner laissé hors bascule (§6.13 point 3) attend encore sa reprise
   (§8.3, « Reprise d'un owner laissé hors bascule ») : la copie figée est alors sa seule source ; la garder (rôle en
   lecture seule compris) **et** les lignes « Bascule Bref » de `.env` (points 1–2 reportés : la reprise lit la copie)
   jusqu'à sa reprise.
   ```bash
   docker exec keevo_postgres psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE bref_bascule" \
     -c "DROP ROLE keevo_bref_ro"
   ```
4. Supprimer les dumps de travail (`shred -u` sur les deux serveurs : `bref-bascule-*.dump*`, et tout fichier d'export
   d'owner `EXPORT` éventuellement produit pour un test — ils contiennent des jetons Meta en clair). S'il faut garder une
   archive jusqu'à l'epic 25, une seule copie **chiffrée** (`gpg --symmetric`), hors des serveurs web, avec la phrase de
   passe dans le coffre de l'équipe.
5. Révoquer les accès temporaires créés pour la bascule : clés SSH ou comptes ajoutés pour l'exploitant, jetons d'API
   ou mots de passe partagés le jour J (changer le mot de passe du super-admin Keevo s'il a circulé).
6. api-bref reste **arrêté** (conteneur et volume conservés) jusqu'à la story 25.1 ; ne pas le rallumer.

## 11. Messages (fr et en)

Remplacer avant l'envoi : `<DATE_ARRET>` (ex. de forme : « samedi JJ/MM à HH h »), `<DATE_RETOUR>`, `<NUMERO_SUPPORT>`,
`<STORES>` (« le Play Store ou l'App Store » / « the Play Store or the App Store »), `<INFO>` (une phrase d'état).
WhatsApp : modèles approuvés (§4.1), variables statiques dans l'ordre indiqué. SMS ≤ 480 caractères (Keevo) ; titre ≤ 100.
Les `<br>` des tableaux sont des retours à la ligne. Corps WhatsApp (`bodyText`) = texte exact du modèle approuvé.

### 11.1 Annonce (J-7) — modèle `bref_bascule_annonce`, variables {{1}} = `<DATE_ARRET>`, {{2}} = `<DATE_RETOUR>`

| | fr | en |
|---|---|---|
| Titre (notification, objet d'e-mail) | Bref Point passe à sa nouvelle version | Bref Point is moving to its new version |
| SMS / notification | Bref Point passe à sa nouvelle version. Arrêt du service le `<DATE_ARRET>`, retour au plus tard le `<DATE_RETOUR>`. Avant l'arrêt, ouvrez l'app avec internet pour envoyer vos ventes en attente : une vente restée hors connexion ne sera pas reprise. | Bref Point is moving to its new version. The service stops on `<DATE_ARRET>` and is back by `<DATE_RETOUR>`. Before the stop, open the app with internet to send your pending sales: a sale left offline will not be carried over. |
| E-mail | Bonjour,<br><br>Bref Point passe à sa nouvelle version. Le service sera arrêté le `<DATE_ARRET>` et reviendra au plus tard le `<DATE_RETOUR>`.<br><br>Avant l'arrêt, ouvrez l'application Bref Point avec une connexion internet pour envoyer vos ventes en attente. Une vente restée hors connexion sur un téléphone ne sera pas reprise. Pendant l'arrêt, notez vos ventes sur papier : vous les saisirez dans la nouvelle version.<br><br>Vos boutiques, factures, clients et votre abonnement seront conservés. Vous vous connecterez avec vos identifiants habituels.<br><br>Questions : `<NUMERO_SUPPORT>`.<br><br>L'équipe Bref Point | Hello,<br><br>Bref Point is moving to its new version. The service will stop on `<DATE_ARRET>` and will be back by `<DATE_RETOUR>` at the latest.<br><br>Before the stop, open the Bref Point app with an internet connection to send your pending sales. A sale left offline on a phone will not be carried over. During the stop, write your sales down on paper: you will enter them in the new version.<br><br>Your shops, invoices, customers and subscription will be kept. You will sign in with your usual credentials.<br><br>Questions: `<NUMERO_SUPPORT>`.<br><br>The Bref Point team |

### 11.2 Rappel (J-1) — modèle `bref_bascule_rappel`, {{1}} = `<DATE_ARRET>`

| | fr | en |
|---|---|---|
| Titre | Rappel : arrêt de Bref Point le `<DATE_ARRET>` | Reminder: Bref Point stops on `<DATE_ARRET>` |
| SMS / notification | Rappel : Bref Point s'arrête le `<DATE_ARRET>` pour passer à sa nouvelle version. Ouvrez l'app avec internet dès maintenant pour envoyer vos ventes en attente. Pendant l'arrêt, notez vos ventes sur papier. | Reminder: Bref Point stops on `<DATE_ARRET>` to move to its new version. Open the app with internet now to send your pending sales. During the stop, write your sales down on paper. |
| E-mail | Bonjour,<br><br>Rappel : Bref Point sera arrêté le `<DATE_ARRET>` et reviendra au plus tard le `<DATE_RETOUR>`.<br><br>Ouvrez dès maintenant l'application avec une connexion internet pour envoyer vos ventes en attente. Les ventes enregistrées dans l'application pendant l'arrêt ne seront pas reprises : notez-les sur papier et saisissez-les dans la nouvelle version.<br><br>L'équipe Bref Point | Hello,<br><br>Reminder: Bref Point will stop on `<DATE_ARRET>` and will be back by `<DATE_RETOUR>` at the latest.<br><br>Open the app now with an internet connection to send your pending sales. Sales recorded in the app during the stop will not be carried over: write them down on paper and enter them in the new version.<br><br>The Bref Point team |

### 11.3 « C'est fait » (J0 après le « go ») — modèle `bref_bascule_terminee`, {{1}} = `<STORES>`

| | fr | en |
|---|---|---|
| Titre | La nouvelle version de Bref Point est disponible | The new version of Bref Point is available |
| SMS / notification | La nouvelle version de Bref Point est disponible. Mettez à jour l'app depuis `<STORES>` et connectez-vous avec vos identifiants habituels. Vos données ont été reprises ; saisissez maintenant les ventes notées pendant l'arrêt. | The new version of Bref Point is available. Update the app from `<STORES>` and sign in with your usual credentials. Your data has been carried over; you can now enter the sales written down during the stop. |
| E-mail | Bonjour,<br><br>La nouvelle version de Bref Point est disponible. Mettez à jour l'application depuis `<STORES>`, puis connectez-vous avec vos identifiants habituels (un nouveau mot de passe peut vous être demandé).<br><br>Vos boutiques, factures, clients et votre abonnement ont été repris. Vous pouvez maintenant saisir les ventes notées pendant l'arrêt.<br><br>Questions : `<NUMERO_SUPPORT>`.<br><br>L'équipe Bref Point | Hello,<br><br>The new version of Bref Point is available. Update the app from `<STORES>`, then sign in with your usual credentials (you may be asked to choose a new password).<br><br>Your shops, invoices, customers and subscription have been carried over. You can now enter the sales written down during the stop.<br><br>Questions: `<NUMERO_SUPPORT>`.<br><br>The Bref Point team |

Phrase à ajouter à la fin de l'e-mail et de la notification (les campagnes Keevo ne visent que les owners, §11.6) :
« Prévenez votre équipe : chacun met à jour l'app et se connecte avec ses identifiants habituels. » / "Please tell your
team: everyone updates the app and signs in with their usual credentials."

Variante si l'équipe Apple diffère (§4.2 point 4), à ajouter à l'e-mail et à la notification : « Vous utilisiez
Apple pour vous connecter ? Connectez-vous avec votre numéro de téléphone et le code reçu, puis reliez Apple depuis
votre profil. » / « Did you sign in with Apple? Sign in with your phone number and the code you receive, then link Apple
again from your profile. »

### 11.4 Incident — modèle `bref_bascule_incident`, {{1}} = `<INFO>`, {{2}} = `<NUMERO_SUPPORT>`

| | fr | en |
|---|---|---|
| Titre | Information sur la mise à jour de Bref Point | Information about the Bref Point update |
| `<INFO>` « reportée » (retour arrière, envoyé par Bref) | La mise à jour est reportée : Bref Point fonctionne de nouveau comme avant. Nous vous donnerons une nouvelle date. | The update is postponed: Bref Point works again as before. We will give you a new date. |
| `<INFO>` « en cours » (après le « go », envoyé par Keevo) | Un problème touche `<la fonction concernée>` depuis `<heure>`. Nous le corrigeons ; vos données sont conservées. | An issue affects `<the feature concerned>` since `<time>`. We are fixing it; your data is safe. |
| SMS / notification / e-mail | « Passage de Bref Point à sa nouvelle version : `<INFO>` Vos boutiques, factures et clients restent conservés. Questions : `<NUMERO_SUPPORT>`. » | "Bref Point update: `<INFO>` Your shops, invoices and customers remain safe. Questions: `<NUMERO_SUPPORT>`." |

### 11.5 Envoi avant le « go » : campagnes de l'admin Bref (J-7, J-1, incident « reportée »)

Dans l'admin Bref › Campagnes (ou `POST https://<hôte Bref>/api/v1/campaigns` avec un jeton super-admin de Bref) : **une
campagne par canal**, créée sans date d'envoi (elle reste en brouillon), relue, puis envoyée (`POST …/campaigns/<id>/send`
ou bouton « Envoyer »). Corps de la campagne WhatsApp de l'annonce (les autres canaux : `"type"` = `"sms"`, `"push"` ou
`"email"`, sans `metadata`) :

```json
{
  "name": "Bascule — annonce J-7 (fr)",
  "type": "whatsapp",
  "title": "Bref Point passe à sa nouvelle version",
  "message": "<texte SMS / notification du §11.1>",
  "targetingCriteria": { "isActive": true, "roles": ["owner", "admin", "seller"] },
  "metadata": { "whatsapp": {
    "templateName": "bref_bascule_annonce", "language": "fr", "headerFormat": "NONE",
    "bodyText": "<corps exact du modèle, docs/whatsapp/modeles-meta-plateforme.md §5>",
    "bodyVariables": [ { "source": "static", "value": "<DATE_ARRET>" }, { "source": "static", "value": "<DATE_RETOUR>" } ]
  } }
}
```

Version anglaise : `"language": "en"`, textes du §11.1 colonne en, et une cible limitée aux boutiques anglophones
connues du support (`"targetingCriteria": {"isActive": true, "shopIds": ["<id boutique>", …]}`) ; ces comptes reçoivent
aussi la version française (pas de critère de langue). Rappel et incident : même forme avec leur modèle et leurs
variables.

### 11.6 Envoi après le « go » : campagnes plateforme Keevo (21.6) — « c'est fait », incident « en cours »

Les campagnes Keevo visent les owners d'entreprises actives ; on les limite aux entreprises activées par la bascule avec
`segment.tenantIds` (100 au plus par campagne, d'où des lots). Rien n'est envoyé à la création : la campagne est en
brouillon jusqu'au `POST …/send`.

Une campagne Keevo a un seul `message` pour tous ses canaux (480 caractères au plus avec le SMS) : la première campagne
(notification, WhatsApp, SMS) porte le texte court du §11 ; le texte long de l'e-mail part dans une seconde campagne
avec le seul canal `EMAIL` (1 000 caractères au plus), créée par le même script — un owner ne reçoit donc qu'un e-mail.

**Vendeurs et gérants.** Les campagnes Keevo ne visent que les **owners** (rôle OWNER). Les membres (gérants, vendeurs)
reçoivent l'annonce et le rappel par Bref (§11.5) mais pas le « c'est fait » : l'e-mail et la notification aux owners
leur demandent de transmettre à leur équipe (« Prévenez votre équipe : chacun met à jour l'app et se connecte avec ses
identifiants habituels. »), et le support répond aux membres qui écrivent au numéro plateforme.

```bash
# (serveur Keevo) la liste des entreprises activées — des identifiants, pas de secret
cd ~/keevo/bascule && set -a && . ../.env && set +a
CUTOVER=<identifiant de la bascule, §14>
docker exec keevo_postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT tenant_id FROM \
  public.migration_cutover_owners WHERE cutover_id = '$CUTOVER' AND status = 'ACTIVATED' AND tenant_id IS NOT NULL \
  ORDER BY position" > activees.txt && wc -l activees.txt
```

```bash
# (poste, bash) dans un dossier de travail vide
mkdir -p ~/bascule-campagnes && cd ~/bascule-campagnes && rm -f activees.txt lot_*
scp <utilisateur>@<serveur Keevo>:keevo/bascule/activees.txt .
[ -s activees.txt ] || echo "Liste vide : ne rien créer"
split -l 100 -d activees.txt lot_      # lot_00, lot_01, …
cat > terminee-fr.json <<'JSON'
{
  "description": "Bascule Bref (24.13) — message « c'est fait »",
  "channels": ["PUSH", "WHATSAPP", "SMS"],
  "title": "La nouvelle version de Bref Point est disponible",
  "message": "<texte SMS / notification du §11.3, <STORES> remplacé>",
  "whatsapp": {
    "templateName": "bref_bascule_terminee", "language": "fr", "headerFormat": "NONE",
    "bodyText": "<corps exact du modèle, docs/whatsapp/modeles-meta-plateforme.md §5>",
    "variables": [ { "source": "STATIC", "value": "le Play Store ou l'App Store" } ]
  }
}
JSON
cat > terminee-fr-email.json <<'JSON'
{
  "description": "Bascule Bref (24.13) — message « c'est fait » (e-mail)",
  "channels": ["EMAIL"],
  "title": "La nouvelle version de Bref Point est disponible",
  "message": "<texte e-mail du §11.3, retours à la ligne au lieu des <br>, <STORES> et <NUMERO_SUPPORT> remplacés>"
}
JSON
shopt -s nullglob
for f in lot_*; do
  for body in terminee-fr.json terminee-fr-email.json; do
    jq --arg name "Bascule — c'est fait ($f, ${body%.json})" --rawfile ids "$f" \
       '. + {name: $name, segment: {tenantIds: ($ids | split("\n") | map(select(length > 0)))}}' "$body" \
    | curl -s -X POST "$API/api/v1/admin/campaigns" "${H[@]}" -d @- \
    | jq -r '[.data.id, .data.statusLabel, (.data.segment.tenantIds | length), .error] | @tsv'
  done
done
```

Attendu : une ligne « `<id>` Brouillon » par lot. Ensuite, pour chaque campagne : relire dans le tableau de bord
(aperçu, nombre de destinataires par canal), puis `curl -s -X POST "$API/api/v1/admin/campaigns/<id>/send" "${H[@]}"`.
Version anglaise : `terminee-en.json` (`"language": "en"`, textes colonne en, `"the Play Store or the App Store"`) pour
la liste des entreprises anglophones connues. Incident « en cours » : même procédure avec `bref_bascule_incident` (deux
variables : `<INFO>`, `<NUMERO_SUPPORT>`).

## 12. Contacts

À remplir à J-7 (ne pas versionner de numéro personnel : garder ce tableau rempli dans le coffre de l'équipe).

| Rôle | Nom | Téléphone | Joignable |
|---|---|---|---|
| Responsable de la bascule (décision « go ») | `<nom>` | `<téléphone>` | toute la fenêtre |
| Exploitant serveurs Bref et Keevo | `<nom>` | `<téléphone>` | toute la fenêtre |
| Super-admin Keevo | `<nom>` | `<téléphone>` | toute la fenêtre |
| Super-admin Bref (campagnes) | `<nom>` | `<téléphone>` | J-7, J-1, J0 |
| Développeur backend Keevo (correctif §7) | `<nom>` | `<téléphone>` | toute la fenêtre |
| Développeur app React Native | `<nom>` | `<téléphone>` | J0 publication |
| Support owners | `<nom>` | `<NUMERO_SUPPORT>` | J0 → J+7 |
| Fournisseurs : RevenueCat, MyCoolPay, Meta | — | consoles des fournisseurs | — |

## 13. Actions utilisateur (récapitulatif)

| # | Action | Quand | Section | Retour arrière |
|---|---|---|---|---|
| 1 | Fournir un dump de la prod Bref pour la répétition 24.12 (protégé, jamais versionné) | dès que possible | 4.4 | — |
| 2 | Soumettre les 8 modèles WhatsApp de la bascule à Meta | J-10 | 4.1 | — |
| 3 | Vérifier l'équipe Apple (Team ID) et le bundle de Bref Point ; `APPLE_BUNDLE_IDS`, `GOOGLE_CLIENT_IDS` | J-10 | 4.2 | — |
| 4 | Soumettre l'app React Native en publication manuelle ; la publier après le « go » | J-7 / J0 | 4.3, 6.16 | ne pas publier si « no-go » |
| 5 | Confirmer l'hôte de l'API Bref et le proxy (option A ou B) | avant J0 | 1.3 | — |
| 6 | Repointer les webhooks RevenueCat, MyCoolPay, Meta vers Keevo | J0 pendant la commande | 2, 6.9 | 2.5 |
| 7 | Installer la redirection des anciens liens publics | J0 après le « go » | 1.4, 6.15 | 1.6 |
| 8 | Arrêt d'api-bref, dump, restauration, rôle, réglage du backend | J0 | 6.1–6.7 | 8.1 |
| 9 | Révoquer les clés et accès temporaires, supprimer copie, rôle et dumps | J+7 | 10 | — |

## 14. Fiche de chronométrage et de décision (remplie par 24.12, puis le jour J)

| Étape | Répétition 1 (24.12) | Répétition 2 (24.12) | J0 |
|---|---|---|---|
| Owners `N` / parallélisme retenu | | | |
| 6.1 arrêt | | | |
| 6.2 dump (taille) | | | |
| 6.3 transfert | | | |
| 6.4–6.5 restauration + index | | | |
| 6.6–6.7 rôle + backend | | | |
| 6.8 commande (moyenne / owner, owner le plus lourd) | | | |
| 6.9 webhooks + tests | | | |
| 6.10–6.13 rapport, cas, décision | | | |
| 6.14 « go » | | | |
| 6.15 liens | | | |
| **Durée totale de coupure** | | | |
| 8.1 retour arrière complet | | | |
| Owners en écart / bloqués (causes, décision) | | | |
| Équipe Apple identique (oui / non) | | | |
| Critères 6.13 cochés (1–7) et signature du responsable | — | — | |

**Statut : en attente de 24.12** (dump de prod à fournir par l'utilisateur). Les colonnes « Répétition » sont remplies
par 24.12 ; la durée annoncée aux owners est la plus longue mesurée, arrondie à l'heure supérieure, plus la marge du §0.2.
