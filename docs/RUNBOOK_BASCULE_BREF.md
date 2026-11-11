# Runbook de la bascule Bref → Keevo (ADR-10)

> **Statut (11/11/2026).** Document versionné avec la configuration du proxy (`keevo/ops/traefik/bref-legacy-links.yml`).
> La story **24.10** y a écrit les sections « Liens publics Bref » et « Webhooks des fournisseurs » (actions
> utilisateur). La story **24.13** complète le reste du runbook : annonces J-7 / J-1, arrêt de Bref, dump et
> restauration, commande de bascule (24.9), réconciliation (24.8), décision « go » / « no-go », publication de l'app
> React Native (23.12), vérifications J+1 / J+7, contacts, messages (21.6). Les durées réelles viennent de la
> répétition générale (24.12).
>
> Rien dans ce document n'est appliqué automatiquement : chaque étape marquée **[UTILISATEUR]** se fait à la main, sur
> le serveur ou dans le tableau de bord du fournisseur.

## Ordre dans la journée de bascule

| Moment | Étape | Section |
|---|---|---|
| Avant J0 | Confirmer l'hôte de l'API Bref et les valeurs des secrets (tableau ci-dessous) | 1.3, 2 |
| J0, Bref arrêté | Repointer les trois webhooks vers Keevo (RevenueCat, MyCoolPay, Meta) | 2 |
| J0, après le « go » et l'activation globale (24.9) | Installer la redirection des liens publics Bref | 1.4 |
| J0, juste après | Contrôles : 5 liens réels (302 → 200), 1 événement de test par fournisseur | 1.5, 2.4 |
| Retour arrière (avant le « go » seulement) | Défaire 1 et 2, rallumer Bref | 1.6, 2.5 |

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

> Écrit par 24.9 (outil livré). L'enchaînement complet de la journée (annonces, arrêt, dump, publication de l'app) reste
> à rédiger par 24.13 ; les durées réelles viennent de la répétition générale 24.12.

### 3.1 [UTILISATEUR] Préparer la copie restaurée

1. Restaurer le dump de Bref (pris **après** l'arrêt d'api-bref) dans une base à part, par exemple `bref_bascule`.
2. En tant que propriétaire de cette base, créer les index d'aide (jamais sur Bref lui-même) :
   `psql -d bref_bascule -f keevo/ops/migration/bref-copy-indexes.sql` (idempotent, 11 index, puis `ANALYZE`).
   Sans eux chaque owner parcourt les tables Bref non indexées (coût O(owners × table)) ; le rapport affiche
   « index de la copie : n / 11 ».
3. Créer un rôle en lecture seule (`GRANT SELECT` sur le schéma `public`, `default_transaction_read_only = on`) et
   le donner au backend : `KEEVO_MIGRATION_BREF_JDBC_URL`, `…_USERNAME`, `…_PASSWORD` (optionnel :
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

- **Owners acceptés** : un owner en écart, bloqué ou en échec ne passe le « go » que s'il figure dans
  `acceptedOwners` avec un motif (10 caractères au moins, l'écart documenté par la répétition 24.12). Il est activé si
  son import est complet ; sinon il reste hors de la bascule (à reprendre à la main).
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

Lecture : le coût est surtout **fixe par owner** (création de l'entreprise et de son schéma, provisionnement des
modules : ≈ 6,5 s), le volume de documents ajoute ≈ 1 s par 1 000 documents ; la réconciliation pèse ≈ 1 % (≈ 90 ms
par owner). Extrapolation à vérifier par 24.12 : durée ≈ owners × 6,8 s ÷ parallélisme + owner le plus lourd, soit
pour 1 000 owners ≈ 29 min au parallélisme 4 (≈ 15–20 min au parallélisme 8 si la base de prod le supporte). 24.12
mesure sur la copie de prod (nombre réel d'owners, parallélisme 4 puis 8) et fixe la fenêtre de coupure annoncée.
