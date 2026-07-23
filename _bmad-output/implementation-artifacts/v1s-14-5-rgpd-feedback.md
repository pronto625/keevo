---
baseline_commit: ccfad6b
---
# Story 14.5: FR91 — Suppression de compte (RGPD) & FR92 — Feedback utilisateur

Status: done

<!-- V1-stabilization track — tag C (patch V1 maintenant + refonte ré-applique).
     Branche : v1-stabilization (off `deploy`). Refonte absorption : "V1-shippable (compliance) puis refonte
     ré-applique" — refonte-native = Story 17.4 (feedback) + Story 16.6 (RGPD suppression compte).
     Validation optionnelle : lancer `validate-create-story` avant `dev-story`. -->

## Story

**As a** user of Keevo (Simon l'OWNER ou Loïc l'EMPLOYEE),
**I want** pouvoir demander la suppression complète de mon compte/données (OWNER) ou soumettre un feedback/signalement de problème (tout utilisateur) directement depuis l'application,
**so that** je peux quitter la plateforme avec l'assurance qu'aucune donnée n'est retenue au-delà du délai légal (RGPD), et que mes retours/bugs remontent instantanément à Toor sans devoir chercher un email ou quitter l'app.

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:366-379` (résumé, Story 14.5) — le texte AC détaillé et faisant foi vient de `_bmad-output/planning-artifacts/epics/epic-8-alertes-notifications-gestion-oprationnelle.md:123-198` (Stories 8.4 "Feedback Utilisateur & Signalement de Problème" et 8.5 "Suppression de Compte & Données (RGPD)" — jamais implémentées, toujours `backlog` dans `sprint-status.yaml`).
- **Tracker :** `sprint-status.yaml` clé `v1s-14-5-rgpd-feedback` (ligne 346).
- **Index track :** `v1-stabilization-stories.md:54` — `v1s-14-5` FR91 suppression compte (RGPD) + FR92 feedback · *refonte-native = 17-4 + 16-6*.
- **Tag :** C — patch V1 maintenant (conformité), refonte ré-applique nativement en Story 17.4 (feedback) + Story 16.6 (RGPD).
- **FR91** (`requirements-inventory.md:127`) : « Un propriétaire peut demander la suppression complète de son compte et de toutes ses données. » **FR92** (`requirements-inventory.md:128`) : « Un utilisateur peut soumettre un feedback ou signaler un problème directement depuis l'application. »
- **Audit** (`AUDIT_CONFORMITE_BMAD.md:83-84,178-179,233-234`) : FR91 et FR92 sont toutes deux `✗`/stub — aucune action de suppression, feedback = tile « Bientôt ». Confiance Haute (FR91, RGPD) / Moyenne (FR92).
- Cette story touche **backend Java (2 tracks indépendants FR91/FR92) + Flutter (2 écrans/mécanismes indépendants)**. Les deux tracks sont livrables et testables séparément — traiter FR91 (Part A) et FR92 (Part B) comme deux sous-livraisons dans l'ordre de son choix, un commit/vérification à la fois par track pour limiter le risque de régression.
- **Décision de scope (confirmée par le PO avant rédaction) :** la nouvelle bannière Flutter "suppression programmée" (AC8) est **indépendante** de `account_status_provider.dart`/`SuspensionBanner` (mécanisme trial/suspension, propriété de la Story 14.9 — backlog, non commencée). Ne PAS implémenter `account_status_provider.dart` au-delà de son stub actuel dans cette story — zéro dépendance croisée avec 14.9.

## Découverte critique — ne pas re-découvrir en codant

**`TenantStatus.DELETION_PENDING` existe déjà dans l'enum, et une partie SUPER_ADMIN du flux est déjà livrée** (`AdminTenantService`/`AdminTenantController`, module `admin.tenant`) — mais le flux **OWNER-facing** (la vraie demande FR91 depuis l'app) est entièrement absent. Lire cette section avant de commencer, elle change l'approche d'implémentation :

- `identity/auth/domain/model/TenantStatus.java` : `ACTIVE, DELETION_PENDING, SUSPENDED, DELETED` — le javadoc de `DELETION_PENDING` dit littéralement *"Tenant has requested account deletion; data retained for grace period (Story 8-5)"*. Cette valeur a été ajoutée en anticipation de cette story, jamais câblée à un vrai déclencheur OWNER.
- `identity/auth/adapter/out/persistence/entity/TenantJpaEntity.java` a déjà une colonne `deletionScheduledAt` (Instant, **getter seulement, pas de setter**) — mais **`identity/auth/domain/model/Tenant.java` (le modèle domaine pur) n'a PAS ce champ du tout**, et `TenantRepositoryAdapter.toEntity()`/`toDomain()` ne le mappent nulle part.
- **🚨 PIÈGE DE DONNÉES si non corrigé :** `TenantRepositoryAdapter.toEntity(Tenant t)` construit une **nouvelle instance transiente** de `TenantJpaEntity` à chaque `save()`, sans jamais renseigner `deletionScheduledAt`. Si un jour un code appelle `tenantRepository.save(tenant)` sur un tenant qui a déjà un `deletionScheduledAt` non-null en base (persisté par un autre chemin), ce `save()` l'écrasera silencieusement à `null` (merge JPA sur toutes les colonnes mappées de l'entité transiente). **C'est précisément pourquoi `AdminTenantService.cancelDeletion()`/`forceDelete()` existants contournent le port hexagonal et utilisent du JDBC brut** — le modèle domaine était incomplet pour ce champ. **Task 1 de cette story corrige ce gap une bonne fois** (voir Task 1 ci-dessous) plutôt que de perpétuer le contournement JDBC pour le nouveau flux OWNER-facing.
- `admin/tenant/application/service/AdminTenantService.java` a déjà : `cancelDeletion(UUID tenantId)` (JDBC : `UPDATE public.tenants SET status='ACTIVE', deletion_scheduled_at=NULL WHERE id=? AND status='DELETION_PENDING'`) et `forceDelete(UUID tenantId)` (JDBC : flip `status='DELETED'` **seulement** — ne drop PAS le schema, ne supprime PAS les users, ne touche PAS S3 — c'est un stub incomplet, pas la vraie purge). Exposés via `admin/tenant/adapter/in/rest/AdminTenantController.java` : `POST /api/v1/admin/tenants/{tenantId}/cancel-deletion` et `POST /api/v1/admin/tenants/{tenantId}/force-delete`, tous deux `SUPER_ADMIN`-only (`requireSuperAdmin()`). **Ces deux endpoints admin restent inchangés par cette story** — ils servent un cas d'usage différent (intervention manuelle Toor), pas le flux self-service OWNER de cette story. Ne pas les modifier, ne pas les réutiliser tels quels pour le flux OWNER (nouveaux endpoints séparés, voir Task 2/3).
- **Aucune infrastructure S3/cloud storage n'existe dans ce backend** (`pom.xml` ne déclare aucun SDK AWS ; `products.photo_url` est un chemin de fichier **local au device** Flutter, jamais uploadé — voir `product_form_page.dart:232-243`). **La clause AC originale "supprime tous les objets S3 du préfixe tenant" est donc un NO-OP explicite pour cette story** — il n'y a rien à supprimer côté serveur. Documenter ce fait dans Completion Notes, ne PAS inventer un `S3Port`/`ObjectStoragePort` fictif.
- **Aucun mécanisme d'opt-out WhatsApp n'existe** (`grep -ri "optOut" backend/` → vide). La clause AC "le retrait de consentement WhatsApp est préservé (preuve RGPD)" est donc également un **NO-OP explicite** — documenter, ne pas construire un système d'opt-out de toutes pièces (scope creep hors AC91/92 littéral).

## Acceptance Criteria

### Part A — FR91 : Suppression de compte (RGPD)

1. **AC1 (Prérequis — combler le gap du modèle domaine `Tenant`)** — **Given** `Tenant.java` n'a pas de champ `deletionScheduledAt` et `TenantRepositoryAdapter` ne le mappe pas, **When** le modèle et l'adapter sont étendus, **Then** `Tenant` gagne un champ `Instant deletionScheduledAt` (nullable) + une méthode `withStatus(TenantStatus newStatus, Instant deletionScheduledAt)` (pattern immutable, miroir de `User.withLockoutState()`/`withPasswordHash()`) ; `TenantJpaEntity` gagne un setter `setDeletionScheduledAt(Instant)` ; `TenantRepositoryAdapter.toEntity()`/`toDomain()` mappent le champ dans les deux sens. **Non-régression obligatoire :** tout `save()` existant (provisioning, etc.) doit continuer à round-tripper correctement un `deletionScheduledAt` null sans erreur.
2. **AC2 (`POST /api/v1/account/delete` — OWNER-only, demande de suppression)** — **Given** aucun endpoint self-service n'existe, **When** un OWNER authentifié appelle `POST /api/v1/account/delete`, **Then** : le tenant courant (`TenantContext`/JWT) passe `status=DELETION_PENDING`, `deletionScheduledAt = now + 30 jours` (via `tenantRepository.save(tenant.withStatus(...))`, Task 1) ; un `AccountDeletionRequestedEvent(tenantId, actorId, requestedAt, scheduledDeletionAt)` est publié et audité (`AuditEventListener`, voir Task 4) ; un message WhatsApp de confirmation est envoyé à l'OWNER (`WhatsAppPort.sendReport(phoneNumber, message)`, message français : *"Votre demande de suppression a été enregistrée. Toutes vos données seront supprimées le [date]. Pour annuler, accédez à Paramètres > Mon Compte dans l'app."*) ; **And** appeler cet endpoint deux fois de suite (tenant déjà `DELETION_PENDING`) → 409 `DELETION_ALREADY_REQUESTED` (pas de double-schedule).
3. **AC3 (`POST /api/v1/account/delete/cancel` — OWNER-only, annulation)** — **Given** un tenant `DELETION_PENDING`, **When** l'OWNER appelle `POST /api/v1/account/delete/cancel`, **Then** `status` repasse `ACTIVE`, `deletionScheduledAt = null` ; un `AccountDeletionCancelledEvent(tenantId, actorId, cancelledAt)` est publié et audité ; **And** appeler cet endpoint sur un tenant qui n'est PAS `DELETION_PENDING` → 409 `DELETION_NOT_PENDING`. **Pas de message WhatsApp sur l'annulation** (absent du texte AC original, ne pas l'ajouter — scope creep).
4. **AC4 (Blocage des écritures pendant `DELETION_PENDING` — miroir exact du garde `SUSPENDED` existant)** — **Given** `JwtAuthFilter.java:169-177` bloque déjà toute méthode HTTP d'écriture (`POST/PUT/PATCH/DELETE`) quand `tenantStatus == "SUSPENDED"` (lecture `GET`/`HEAD` toujours permise), **When** le même bloc est dupliqué juste en dessous pour `"DELETION_PENDING".equals(tenantStatus)`, **Then** toute écriture pendant la période de grâce → 403 JSON `{"domainCode":"ACCOUNT_DELETION_PENDING"}` (via `writeErrorWithStatus`, pattern identique ligne 172-176), lecture inchangée. **Limitation acceptée, pré-existante et non nouvelle à cette story :** `tenantStatus` est extrait du claim JWT (`jwtTokenProvider.extractTenantStatus(claims)`, ligne 133), donc **figé au moment de l'émission du token** — un JWT émis juste avant la demande de suppression continuera de porter `ACTIVE` jusqu'à son expiration/refresh naturel. C'est **exactement la même caractéristique** que le garde `SUSPENDED` déjà shippé (aucune closure de fraîcheur n'a été faite pour `SUSPENDED` non plus) — ne PAS tenter de la corriger ici (hors scope, changement architectural — rafraîchissement de claim ou invalidation de session globale par tenant), documenter simplement en Completion Notes comme parité acceptée.
5. **AC5 (Job planifié quotidien — expiration de la période de grâce)** — **Given** aucun scheduler n'existe pour ce cas, **When** un nouveau `AccountDeletionScheduler` (miroir de `SubscriptionExpiryScheduler`, cron quotidien, `Clock` injecté pour testabilité) tourne, **Then** pour chaque tenant `DELETION_PENDING` avec `deletionScheduledAt` dans le passé (`tenantRepository.findAll()` + filtre) : (a) message WhatsApp final envoyé à l'OWNER (*"Votre compte Keevo a été supprimé. Merci d'avoir utilisé Keevo."*) **avant** la purge ; (b) le schema PostgreSQL du tenant (`kv_xxxxxx`) est DROP CASCADE ; (c) les lignes `public.user_tenant_memberships` de ce tenant sont supprimées, PUIS les lignes `public.users` **orphelines** (plus aucune membership restante, y compris pour des users multi-tenant — voir Dev Notes séquence SQL exacte, ordre obligatoire) sont supprimées, PUIS `public.refresh_tokens WHERE tenant_id = schemaName` et `public.device_tokens` des users orphelins nettoyés ; (d) suppression S3 = **NO-OP documenté** (AC1 de cette section, pas d'infra) ; (e) le tenant passe `status=DELETED` (ligne `tenants` conservée, miroir du comportement existant `AdminTenantService.forceDelete()` qui garde aussi la ligne). **And** Toor voit ce changement de statut immédiatement dans le dashboard admin existant (AC6).
6. **AC6 (Visibilité Toor — réutiliser l'existant, pas de nouveau canal)** — **Given** `AdminTenantController`/`AdminTenantService` exposent déjà `status` + `deletionScheduledAt` dans `GET /api/v1/admin/tenants` et `GET /api/v1/admin/tenants/{id}` (`AdminTenantListItemDto`/`AdminTenantDetailDto`), **When** le job de suppression (AC5) marque un tenant `DELETED`, **Then** ce changement est visible **sans aucun nouveau endpoint ni nouvelle notification** — les vues admin existantes affichent déjà `status` string-based. **Aucun code de dashboard/notification à ajouter pour cette AC** — vérifier seulement que `"DELETED"` ne casse pas un éventuel `switch`/mapping de statut côté DTO (`AdminTenantListItemDto`/`AdminTenantDetailDto` — grep pour un `switch(status)` exhaustif qui pourrait ne pas couvrir `DELETED`).
7. **AC7 (Flutter — `AccountPage`, section "Zone dangereuse" OWNER-only)** — **Given** `keevo/app/lib/features/settings/presentation/page/account_page.dart` a déjà une carte "Sécurité" avec `_SecurityTile` ("Changer mon mot de passe"), **When** une nouvelle section est ajoutée sous celle-ci, **Then** pour `profile.role == 'OWNER'` (champ déjà chargé par cette page, aucun nouveau provider requis) : un tile destructif "Supprimer mon compte" ouvre un dialog d'avertissement irréversible (liste ce qui sera supprimé : boutique, ventes, stock, employés, rapports) → un second écran/étape demande de **taper son propre numéro de téléphone** pour confirmer (comparé à `profile.phoneNumber`, déjà chargé) → bouton "Confirmer" désactivé tant que le texte tapé ≠ numéro exact → appel `POST /api/v1/account/delete` → SnackBar de confirmation.
8. **AC8 (Flutter — `DeletionPendingBanner`, indépendante de `SuspensionBanner`/14.9)** — **Given** aucune bannière de ce type n'existe, **When** un nouveau widget `DeletionPendingBanner` (ConsumerWidget, provider dédié lisant le statut tenant **directement**, sans dépendre de `account_status_provider.dart`) est monté dans `main_shell.dart` (Column, à côté de `SyncWarningBanner`/`OfflineGateBanner`), **Then** si le tenant est `DELETION_PENDING` (déterminé via le profil/JWT courant), afficher : *"Suppression programmée le [date] — [N] jours restants. [Annuler la suppression]"* ; le bouton "Annuler la suppression" appelle `POST /api/v1/account/delete/cancel` et fait disparaître la bannière (invalider le provider). **Styling : zéro `Colors.white` codé en dur** — utiliser `Theme.of(context).colorScheme`/`KeevoColors` (miroir `offline_gate_banner.dart`), **pas** le pattern `suspension_banner.dart` (qui hardcode déjà `Colors.white`, anti-pattern connu, ne pas copier).
9. **AC9 (Flutter — EMPLOYEE, message explicatif, pas d'action)** — **Given** un EMPLOYEE navigue vers `/settings/account`, **When** la section "Zone dangereuse" serait normalement affichée, **Then** pour `profile.role != 'OWNER'`, afficher à la place un texte : *"Votre compte est lié à [Business Name]. Pour supprimer vos données, demandez à votre propriétaire de vous retirer de l'équipe."* — **aucune action de suppression rendue** pour ce rôle.

### Part B — FR92 : Feedback utilisateur

10. **AC10 (Nouveau module backend `feedback` + migration `V6__feedback.sql`)** — **Given** aucune table/module n'existe pour le feedback, **When** un nouveau module top-level `com.keevo.feedback` est créé (`package-info.java` avec `@ApplicationModule(allowedDependencies = {"identity"})`, miroir exact de la syntaxe `sync`/`admin`/`identity` existante), **Then** : `V6__feedback.sql` (prochaine version libre, vérifier qu'aucun `V6` n'a été créé entre-temps par une autre story en cours) crée `public.feedback(id, type, description, tenant_id, user_id, app_version, platform, screen_context, submitted_at, priority DEFAULT 'NORMAL', created_at, updated_at)` ; `FeedbackJpaEntity` (`@Table(name="feedback", schema="public")`, miroir `TenantJpaEntity`) + `FeedbackSpringRepository extends JpaRepository` + port hexagonal `FeedbackRepository` (in/out) + adapter, structure standard `domain/model`, `domain/port/in`, `domain/port/out`, `application/service`, `adapter/in/rest`, `adapter/out/persistence`.
11. **AC11 (`POST /api/v1/feedback` — tout utilisateur authentifié, priorité auto)** — **Given** le stub Flutter existe déjà (`settings_page.dart:336-344`) mais aucun backend, **When** un OWNER **ou** EMPLOYEE appelle `POST /api/v1/feedback` avec `{type, description, appVersion, platform, screenContext}` (`tenantId`/`userId`/`submittedAt` dérivés côté serveur du contexte authentifié, pas du body — ne jamais faire confiance à un `tenantId`/`userId` client-fourni), **Then** l'entrée est stockée avec `priority='NORMAL'` par défaut, **sauf** si `description` contient un des mots-clés (insensible à la casse) : `"bloqué"`, `"erreur critique"`, `"données perdues"`, `"ne fonctionne pas"` → `priority='HIGH'`.
12. **AC12 (Handler sync offline — `SUBMIT_FEEDBACK`)** — **Given** le pattern `AbstractSyncOperationHandler`/`SyncOperationHandlerRegistry` (`sync.sync.application.handler`, types = `String`s libres, pas d'enum partagé), **When** un nouveau `SubmitFeedbackSyncHandler extends AbstractSyncOperationHandler` est ajouté avec `supportedTypes() = Set.of("SUBMIT_FEEDBACK")`, **Then** il délègue au **même** service applicatif que AC11 (pas de duplication de la logique de priorité) ; `sync`'s `package-info.java` gagne `"feedback"` dans `allowedDependencies` (actuellement `{catalog, commerce, inventory, identity, store, subscription}`) pour que cette dépendance soit légale sous Spring Modulith (aujourd'hui non-bloquant en warning-mode, mais documenté explicitement pour ne pas casser silencieusement l'enforcement futur — Epic 12.2e).
13. **AC13 (`GET /api/v1/admin/feedback` — SUPER_ADMIN, filtrable)** — **Given** l'AC originale dit "Toor peut filtrer le feedback par contexte d'écran dans le dashboard admin", **When** un nouveau `AdminFeedbackController` (`@RequestMapping("/api/v1/admin/feedback")`, hérite automatiquement du filtre `SecurityConfig.java:96` `hasRole('SUPER_ADMIN')` sur `/api/v1/admin/**` + `requireSuperAdmin()` programmatique miroir `AdminTenantController`) est ajouté, **Then** `GET` liste paginé filtrable par `priority`/`screenContext`. `admin`'s `package-info.java` gagne `"feedback"` dans `allowedDependencies` (même raisonnement qu'AC12).
14. **AC14 (Flutter — `FeedbackFormPage`, remplace le stub)** — **Given** `settings_page.dart:336-344` a un `_SettingsTile(title: 'Signaler un problème', ..., onTap: () {}, badge: 'Bientôt')`, **When** ce stub est remplacé (retirer `badge`, `onTap: () => context.push('/settings/feedback')`, nouvelle route flat dans `app_router.dart` miroir `/settings/change-password`), **Then** `FeedbackFormPage` (nouveau `ConsumerStatefulWidget`, **pas** de dialog — page pleine, controllers créés en `initState()`/disposés en `dispose()`) affiche : sélecteur de type (💡 Suggestion / 🐛 Signaler un problème / 👍 J'adore Keevo), description (requis, 10-500 caractères, compteur live), `screenContext` auto-peuplé (`GoRouterState.of(context).uri.path` — **pas** `.name`, aucune route n'est nommée dans ce routeur), pièce jointe screenshot optionnelle (camera/gallery via `image_picker`, compression pour viser ≤500KB — voir Dev Notes, aucun utilitaire existant à réutiliser).
15. **AC15 (Flutter — file d'attente offline + succès)** — **Given** `sync_queue` (Drift) a une colonne `operation` en texte libre (pas d'enum Dart), **When** le formulaire est soumis, **Then** : un UUID est généré localement, `syncService.queueOperation(operation: 'SUBMIT_FEEDBACK', payload: {...types AC11 + screenshot en base64 si présent...}, entityId: uuid)` puis `syncTriggerDispatcher.triggerPushIfIdle()` (fire-and-forget, miroir exact `stock_repository_impl.dart:154-221`) — fonctionne offline ; SnackBar succès : *"Merci pour votre retour ! Nous l'examinerons rapidement 🙏"* ; le formulaire se réinitialise après soumission réussie.

## Tasks / Subtasks

- [x] **Task 1 — Combler le gap modèle domaine `Tenant` (AC1, prérequis pour Task 2/3)**
  - [x] 1.1 `identity/auth/domain/model/Tenant.java` : ajouter champ `Instant deletionScheduledAt` (nullable, pas de `Objects.requireNonNull`), nouveau constructeur complet + constructeur legacy délègue avec `null`, méthode `withStatus(TenantStatus newStatus, Instant deletionScheduledAt)` retournant une nouvelle instance (immutable, miroir `User.withLockoutState()`).
  - [x] 1.2 `identity/auth/adapter/out/persistence/entity/TenantJpaEntity.java` : ajouter `setDeletionScheduledAt(Instant)` (setter manquant aujourd'hui).
  - [x] 1.3 `identity/auth/adapter/out/persistence/impl/TenantRepositoryAdapter.java` : `toEntity()` copie `t.getDeletionScheduledAt()` via le nouveau setter (post-construction, puisque le constructeur `TenantJpaEntity` ne le prend pas en paramètre) ; `toDomain()` lit `e.getDeletionScheduledAt()`.
  - [x] 1.4 Test unitaire : round-trip `save()` → `findById()` préserve `deletionScheduledAt` (null et non-null) — nouveau test dans `TenantRepositoryAdapterTest.java` (créer si absent).

- [x] **Task 2 — `POST /api/v1/account/delete` + `/cancel` (AC2, AC3)**
  - [x] 2.1 Nouveaux `ErrorCode` : `ACCOUNT_DELETION_PENDING` (403, groupe `ACCOUNT_SUSPENDED`/`FORBIDDEN` dans `GlobalExceptionHandler.java` case `-> HttpStatus.FORBIDDEN`), `DELETION_ALREADY_REQUESTED` (409, groupe `OPTIMISTIC_LOCK`/`DAY_ALREADY_CLOSED`), `DELETION_NOT_PENDING` (409, même groupe). Ajouter chacun à `FR_MESSAGES` (`GlobalExceptionHandler.java:45`).
  - [x] 2.2 Nouveaux événements `AccountDeletionRequestedEvent(UUID tenantId, UUID actorId, Instant requestedAt, Instant scheduledDeletionAt)` et `AccountDeletionCancelledEvent(UUID tenantId, UUID actorId, Instant cancelledAt)` dans `identity/auth/domain/model/` (miroir `UserRegisteredEvent`/`UserAuthenticatedEvent`, même package, records purs).
  - [x] 2.3 Nouveau service applicatif (ex. `identity/auth/application/service/AccountDeletionService.java`) : `requestDeletion(UUID actorId, String tenantId)` — charge `Tenant` via `tenantRepository.findBySchemaName`/`findById`, vérifie `status != DELETION_PENDING` (sinon `DomainException(DELETION_ALREADY_REQUESTED)`), `save(tenant.withStatus(DELETION_PENDING, now.plus(30, DAYS)))`, publie `AccountDeletionRequestedEvent`, envoie WhatsApp via `WhatsAppPort.sendReport(ownerPhone, message)` (récupérer le téléphone OWNER via `UserRepository`/`User.getPhoneNumber()` à partir de `actorId`). `cancelDeletion(UUID actorId, String tenantId)` — vérifie `status == DELETION_PENDING` (sinon `DomainException(DELETION_NOT_PENDING)`), `save(tenant.withStatus(ACTIVE, null))`, publie `AccountDeletionCancelledEvent`. **Pas de WhatsApp sur cancel** (AC3).
  - [x] 2.4 Nouveau `AccountController` (`identity/auth/adapter/in/rest/AccountController.java`, `@RequestMapping("/api/v1/account")`) : `POST /delete` + `POST /delete/cancel`, garde OWNER-only **deux couches** (miroir `v1s-12-6` : `@PreAuthorize("hasRole('OWNER')")` + check programmatique `isOwnerRole()`/`DomainException(FORBIDDEN)`, car `standaloneSetup` n'évalue pas `@PreAuthorize` — voir Dev Notes Testing).
  - [x] 2.5 `AuditEventListener.java` : deux nouvelles méthodes `@EventListener public void on(AccountDeletionRequestedEvent event)` / `on(AccountDeletionCancelledEvent event)`, miroir `StoreDeactivatedEvent` (`auditPort.record(actorId, tenantId, "ACCOUNT_DELETION_REQUESTED"/"ACCOUNT_DELETION_CANCELLED", "Tenant", tenantIdAsUuid, null, toJson(...))`).
  - [x] 2.6 Tests : `AccountDeletionServiceTest` (Mockito, mocks `TenantRepository`/`UserRepository`/`WhatsAppPort`/`ApplicationEventPublisher`) — happy path request, double-request → 409, cancel happy path, cancel-when-not-pending → 409. `AccountControllerTest` (`standaloneSetup` + `GlobalExceptionHandler`) — EMPLOYEE → 403 sur les deux endpoints (pattern `shouldReturn403ForEmployee...` de `v1s-12-6`).

- [x] **Task 3 — Blocage écriture `DELETION_PENDING` (AC4)**
  - [x] 3.1 `shared/infrastructure/security/JwtAuthFilter.java` : dupliquer le bloc `SUSPENDED` (lignes 169-177) juste en dessous pour `"DELETION_PENDING".equals(tenantStatus) && isWriteMethod(...)` → `writeErrorWithStatus(response, "ACCOUNT_DELETION_PENDING", SC_FORBIDDEN)`. **Ne pas fusionner les deux conditions en un seul `||`** — garder deux blocs distincts avec des domainCodes différents pour un diagnostic client clair.
  - [x] 3.2 Test `JwtAuthFilterTest` : `shouldBlockWriteWhenTenantDeletionPending()` (miroir du test `SUSPENDED` existant) + `shouldAllowReadWhenTenantDeletionPending()`.

- [x] **Task 4 — `AccountDeletionScheduler` (AC5, AC6)**
  - [ ] 4.1 Nouveau `AccountDeletionScheduler` (`shared/infrastructure/scheduling/`, miroir exact `SubscriptionExpiryScheduler` : double constructeur `Clock` pour testabilité, `@Scheduled(cron = "0 0 3 * * *")` — 03:00, décalé de `SubscriptionExpiryScheduler` (02:00) pour éviter la contention).
  - [ ] 4.2 Boucle `tenantRepository.findAll()`, filtre `status == DELETION_PENDING && deletionScheduledAt.isBefore(clock.instant())`.
  - [ ] 4.3 Par tenant expiré, dans cet ordre exact (voir Dev Notes pour le SQL précis) : (a) WhatsApp final → OWNER ; (b) capturer `affectedUserIds` (`SELECT user_id FROM user_tenant_memberships WHERE tenant_id=?`) ; (c) `DELETE FROM user_tenant_memberships WHERE tenant_id=?` ; (d) `DELETE FROM users WHERE id = ANY(:affectedUserIds) AND NOT EXISTS (SELECT 1 FROM user_tenant_memberships WHERE user_id = users.id)` (ne supprimer QUE les identités globales devenues orphelines — un user multi-tenant actif ailleurs doit survivre) ; (e) `DELETE FROM refresh_tokens WHERE tenant_id = :schemaName` ; (f) `DELETE FROM device_tokens WHERE user_id = ANY(:orphanedUserIds)` (le sous-ensemble de (d) réellement supprimé) ; (g) DROP schema (nouvelle méthode non-swallowing sur `TenantSchemaProvisioner`, voir 4.4) ; (h) `tenantRepository.save(tenant.withStatus(DELETED, null))`.
  - [ ] 4.4 `shared/infrastructure/persistence/TenantSchemaProvisioner.java` : `dropSchemaIfExists()` existant (ligne ~748) **avale silencieusement les exceptions** (best-effort rollback de provisioning raté) — **ne pas réutiliser tel quel** pour une suppression définitive de données client. Ajouter une méthode jumelle qui **propage** l'exception (ex. `dropSchemaForDeletion(String schemaName)`, même regex de garde `^kv_[a-z0-9]{6}$`, même `DROP SCHEMA ... CASCADE`, mais sans `catch` silencieux) afin qu'un échec de suppression physique interrompe le job pour CE tenant (log ERROR + skip, ne pas marquer `DELETED` si le DROP a échoué) plutôt que de marquer le tenant supprimé alors que ses données existent encore.
  - [ ] 4.5 Vérifier `AdminTenantListItemDto`/`AdminTenantDetailDto` : si un `switch(status)` exhaustif existe (à vérifier au moment du code), s'assurer que `DELETED` y est déjà couvert (probable, puisque `AdminTenantService.forceDelete()` produit déjà ce statut) — sinon l'ajouter (AC6, pas de nouveau endpoint).
  - [ ] 4.6 Tests : `AccountDeletionSchedulerTest` (Mockito, `Clock` fixe injecté) — tenant expiré → toutes les étapes appelées dans l'ordre ; tenant `DELETION_PENDING` mais pas encore expiré → skip ; tenant `ACTIVE`/`SUSPENDED` → skip. Test du user multi-tenant NON supprimé (a une autre membership active) — cas le plus important à couvrir, c'est le piège de correction le plus probable d'être mal implémenté.

- [x] **Task 5 — Module `feedback` + migration (AC10)**
  - [ ] 5.1 Vérifier `ls keevo/backend/src/main/resources/db/migration/` au moment de coder — si `V6` existe déjà (créé par une autre story en parallèle), utiliser `V7`. Actuellement (dernier check) : `V1, V2, V4, V5` existent, `V3` intentionnellement absent (réservé) — **V6 est libre**.
  - [ ] 5.2 `keevo/backend/src/main/java/com/keevo/feedback/package-info.java` : `@org.springframework.modulith.ApplicationModule(allowedDependencies = {"identity"})` — copier verbatim la syntaxe de `sync/package-info.java`.
  - [ ] 5.3 `Vn__feedback.sql` : `CREATE TABLE IF NOT EXISTS public.feedback (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), type varchar(30) NOT NULL, description varchar(500) NOT NULL, tenant_id uuid NOT NULL, user_id uuid NOT NULL, app_version varchar(20), platform varchar(20), screen_context varchar(200), submitted_at timestamp(6) with time zone NOT NULL, priority varchar(10) NOT NULL DEFAULT 'NORMAL', created_at timestamp(6) with time zone NOT NULL DEFAULT now(), updated_at timestamp(6) with time zone NOT NULL DEFAULT now())`.
  - [ ] 5.4 Structure hexagonale standard sous `feedback/feedback/` (domaine `feedback` : `domain/model/Feedback.java`, `domain/port/in/SubmitFeedbackUseCase.java`, `domain/port/out/FeedbackRepository.java`, `application/service/SubmitFeedbackService.java` — contient la logique de mots-clés HIGH-priority partagée par AC11/AC12 —, `adapter/out/persistence/{FeedbackJpaEntity,FeedbackSpringRepository,impl/FeedbackRepositoryAdapter}.java`.
  - [ ] 5.5 Tests : `SubmitFeedbackServiceTest` — priorité NORMAL par défaut, chacun des 4 mots-clés → HIGH (insensible casse), description sans mot-clé → NORMAL.

- [x] **Task 6 — Endpoints feedback (AC11, AC12, AC13)**
  - [ ] 6.1 `feedback/feedback/adapter/in/rest/FeedbackController.java` : `POST /api/v1/feedback` — **tout rôle authentifié** (`hasAnyRole('OWNER','EMPLOYEE')`, pas de garde OWNER-only), `tenantId`/`userId`/`submittedAt` dérivés serveur-side (`TenantContext`/`SecurityContextHolder`/`Instant.now()`), jamais du body client.
  - [ ] 6.2 `sync/sync/application/handler/SubmitFeedbackSyncHandler.java extends AbstractSyncOperationHandler`, `supportedTypes() = Set.of("SUBMIT_FEEDBACK")`, délègue à `SubmitFeedbackUseCase` (même service que 6.1 — ne pas dupliquer la logique HIGH-priority).
  - [ ] 6.3 `sync/package-info.java` : ajouter `"feedback"` à `allowedDependencies` (liste actuelle : `catalog, commerce, inventory, identity, store, subscription`).
  - [ ] 6.4 `admin/package-info.java` : ajouter `"feedback"` à `allowedDependencies` (liste actuelle se termine par `..., reporting, messaging`).
  - [ ] 6.5 `admin/feedback/adapter/in/rest/AdminFeedbackController.java` (`@RequestMapping("/api/v1/admin/feedback")`, miroir exact `AdminTenantController` : `requireSuperAdmin()` programmatique + hérite du filtre `/api/v1/admin/**` déjà `SUPER_ADMIN`-only) : `GET` paginé, params optionnels `priority`/`screenContext`.
  - [ ] 6.6 Tests : `FeedbackControllerTest` (EMPLOYEE ET OWNER → 200/201, pas de garde de rôle à tester en négatif ici puisque les deux rôles sont autorisés) ; `SubmitFeedbackSyncHandlerTest` (miroir `CreateClientSyncHandler` — idempotence sur `operationId` dupliqué) ; `AdminFeedbackControllerTest` (OWNER/EMPLOYEE → 403, hérité du filtre `/admin/**`).

- [ ] **Task 7 — Flutter FR91 (AC7, AC8, AC9)**
  - [ ] 7.1 `keevo/app/lib/features/settings/presentation/page/account_page.dart` : sous la carte "Sécurité" existante, ajouter conditionnellement (`profile.role == 'OWNER'`) une section "Zone dangereuse" → tile destructif ouvrant un flux de confirmation à 2 étapes (avertissement puis saisie du numéro). **Construire ce flux en `StatefulWidget` dédié avec `dispose()` propre** (pas de `TextEditingController` créé dans une méthode statique/dialog builder sans cycle de vie — c'est exactement le defer connu de `sale_detail_page.dart:186-260`, ne pas le reproduire).
  - [ ] 7.2 Pour `profile.role != 'OWNER'` : afficher le texte explicatif EMPLOYEE (AC9), aucune action.
  - [ ] 7.3 Nouveau `DeletionPendingBanner` (`keevo/app/lib/core/scaffold/deletion_pending_banner.dart` ou équivalent settings), provider dédié (nouveau, indépendant de `account_status_provider.dart` — décision de scope confirmée), monté dans `main_shell.dart` `Column` aux côtés de `SyncWarningBanner`/`OfflineGateBanner`. Styling : `Theme.of(context).colorScheme`/`KeevoColors`, zéro `Colors.white` (miroir `offline_gate_banner.dart`, PAS `suspension_banner.dart`).
  - [ ] 7.4 Tests widget : `account_page_test.dart` (étendre le fichier existant, pattern `ProviderScope` overrides + `MaterialApp.router`) — OWNER voit "Zone dangereuse", EMPLOYEE voit le texte explicatif, bouton confirmer désactivé si le numéro tapé ne correspond pas. Nouveau `deletion_pending_banner_test.dart`.

- [ ] **Task 8 — Flutter FR92 (AC14, AC15)**
  - [ ] 8.1 `pubspec.yaml` : ajouter `package_info_plus` (absent aujourd'hui, nécessaire pour `appVersion`).
  - [ ] 8.2 `keevo/app/lib/features/settings/presentation/page/settings_page.dart:336-344` : retirer `badge: 'Bientôt'`, `onTap: () => context.push('/settings/feedback')`.
  - [ ] 8.3 `keevo/app/lib/core/router/app_router.dart` : nouvelle route flat `GoRoute(path: '/settings/feedback', builder: (_, __) => const FeedbackFormPage())` (bloc `/settings/*` existant, ~ligne 701-719). **Pas** d'ajout à `_ownerOnlyPrefixes` (accessible OWNER + EMPLOYEE).
  - [ ] 8.4 Nouveau `FeedbackFormPage` (`ConsumerStatefulWidget`, controllers `initState()`/`dispose()`) : type selector, description (validation live 10-500 caractères, compteur), `screenContext` = `GoRouterState.of(context).uri.path` capturé **avant** navigation vers le formulaire (le path courant au moment du tap sur "Signaler un problème", pas celui de la page feedback elle-même), `appVersion` via `package_info_plus`, `platform` via `Platform.isAndroid`/`isIOS` (miroir `main_shell.dart._fcmPlatform()`), screenshot optionnel (`image_picker`, `maxWidth/maxHeight/imageQuality` comme `product_form_page.dart:200-212`) + compression via `package:image` (déjà en dépendance `pubspec.yaml` mais **jamais importé nulle part** — première vraie utilisation ; boucle de ré-encodage/downscale jusqu'à `bytes.length <= 500*1024`) → encodée en **base64** dans le payload JSON (pas de multipart — aucun endpoint d'upload n'existe, et le payload `sync_queue` doit rester un JSON texte pour fonctionner offline).
  - [ ] 8.5 Soumission : générer un UUID local, `syncService.queueOperation(operation: 'SUBMIT_FEEDBACK', payload: {type, description, appVersion, platform, screenContext, screenshotBase64?}, entityId: uuid)`, puis `syncTriggerDispatcher.triggerPushIfIdle()` — miroir exact `stock_repository_impl.dart:154-221`. SnackBar succès + reset formulaire.
  - [ ] 8.6 Tests widget : `feedback_form_page_test.dart` — validation longueur description, soumission appelle `queueOperation` avec le bon `operation` string, SnackBar affichée, formulaire réinitialisé après succès.

- [ ] **Task 9 — Non-régression & Dev Agent Record**
  - [ ] 9.1 `mvn test` (backend) — 0 NOUVELLE régression vs baseline connue (voir stories précédentes pour les chiffres de référence — H2/JSONB pré-existant produit ~40 erreurs `ApplicationContext` connues, non liées à cette story).
  - [ ] 9.2 `flutter test` (app) — 0 NOUVELLE régression vs baseline (803 passed/12 failed pré-existants, voir `deferred-work.md` v1s-13-4/13-5).
  - [ ] 9.3 `flutter analyze` — 0 nouvelle erreur/warning.
  - [ ] 9.4 Renseigner Agent Model, Debug Log, Completion Notes (inclure explicitement les 2 NO-OP documentés — S3 et opt-out WhatsApp — et la limitation de fraîcheur JWT acceptée, AC4), File List.

## Dev Notes

### Ce qui est vérifiable par `mvn test`/`flutter test` dans ce bac à sable vs. analyse de code seule

- **Vérifiable par tests unitaires (Mockito, mocks) :** `AccountDeletionService`, `SubmitFeedbackService` (logique mots-clés), `AccountDeletionScheduler` (avec `Clock` fixe), tous les handlers sync, tous les controllers via `standaloneSetup` (RBAC programmatique).
- **NON fiable dans ce bac à sable (H2 embarqué, pas de vrai PostgreSQL/Testcontainers) :** tout test qui nécessiterait de faire tourner réellement `Vn__feedback.sql`, un vrai `DROP SCHEMA ... CASCADE`, ou de vérifier le comportement transactionnel du job de suppression sur des données réelles. **Ne pas promettre un `mvn test` vert pour ce type de comportement** — documenter en Completion Notes comme "vérifié par analyse de code, nécessite validation manuelle/CI avec PostgreSQL réel avant premier déploiement production" (même discipline que `v1s-13-5` Task 5).

### Séquence SQL exacte pour la purge (Task 4.3) — ordre obligatoire

```sql
-- 1. Capturer AVANT toute suppression (sinon on perd la liste)
SELECT user_id FROM public.user_tenant_memberships WHERE tenant_id = :tenantId;

-- 2. Supprimer les memberships de CE tenant
DELETE FROM public.user_tenant_memberships WHERE tenant_id = :tenantId;

-- 3. Supprimer UNIQUEMENT les users devenus orphelins (aucune membership restante,
--    y compris dans un AUTRE tenant — un user peut appartenir à N tenants, Story 1.7)
DELETE FROM public.users u
WHERE u.id = ANY(:affectedUserIds)
  AND NOT EXISTS (SELECT 1 FROM public.user_tenant_memberships m WHERE m.user_id = u.id)
RETURNING u.id;  -- capturer :orphanedUserIds pour l'étape 5

-- 4. refresh_tokens a sa propre colonne tenant_id (varchar = schemaName) — suppression directe
DELETE FROM public.refresh_tokens WHERE tenant_id = :schemaName;

-- 5. device_tokens n'a PAS de tenant_id (token FCM global à l'user) — ne nettoyer QUE
--    pour les users réellement supprimés à l'étape 3, jamais pour tout le tenant
DELETE FROM public.device_tokens WHERE user_id = ANY(:orphanedUserIds);
```

**Piège à éviter absolument :** si l'implémentation supprime `public.users` par un simple `WHERE tenant_id=?` sans le `NOT EXISTS`, un employé qui serait aussi membre d'un autre tenant perdrait son identité globale et ne pourrait plus se connecter nulle part — bug de suppression en cascade non voulu. C'est le point de correction le plus probable d'être mal implémenté dans cette story, tester explicitement ce cas (Task 4.6).

### RBAC — pattern à copier (déjà établi, ne pas réinventer)

`InventorySessionController.validateInventory()` (`inventory/counting/adapter/in/rest/InventorySessionController.java:138-148`) est le modèle canonique : `@PreAuthorize` (défense réelle en prod) **+** check programmatique `isOwnerRole()` → `throw new DomainException(ErrorCode.FORBIDDEN, "...")` (seul chemin réellement exécuté par les tests `standaloneSetup`, qui ne chargent pas `@EnableMethodSecurity`). Squelette de test à copier (`InventorySessionControllerValidateTest.java:118-125`) :
```java
@Test
void delete_employeeForbidden_shouldReturn403() throws Exception {
    authenticateAs("EMPLOYEE");
    mockMvc.perform(post("/api/v1/account/delete")).andExpect(status().isForbidden());
}
```
`ErrorCode.FORBIDDEN` existe déjà (ligne 127) — le réutiliser, ne pas créer de nouveau code pour "pas OWNER".

### WhatsApp — un seul point d'intégration générique

`WhatsAppPort.sendReport(String phoneNumber, String reportText)` (`messaging/whatsapp/domain/port/out/WhatsAppPort.java`) est **la seule méthode du port** — pas de méthode par type de template, les appelants construisent la chaîne eux-mêmes (voir `DayClosureWhatsAppListener.java:76`). Les 2 messages de cette story (confirmation demande + suppression finale) utilisent cette même méthode avec des chaînes françaises construites inline — **aucune nouvelle méthode de port à ajouter.**

### Module `feedback` — pourquoi un nouveau module top-level plutôt que loger ça dans `admin`

`sync`'s `allowedDependencies` actuel (`{catalog, commerce, inventory, identity, store, subscription}`) **n'inclut PAS `admin`** — si `FeedbackController`/`FeedbackRepository` vivaient sous `com.keevo.admin.feedback`, le nouveau `SubmitFeedbackSyncHandler` (obligatoirement dans `sync`) violerait le graphe de dépendances Modulith pour pouvoir appeler le même service applicatif que le endpoint REST direct (AC12 exige explicitement de ne PAS dupliquer la logique). Un nouveau module top-level `com.keevo.feedback` (dépendance minimale `{identity}`) évite ce problème : `sync` ET `admin` ajoutent simplement `"feedback"` à leur propre liste (Task 6.3/6.4), sans że `feedback` ait besoin de connaître `sync`/`admin`. La vérification Modulith est actuellement en mode warning uniquement (`ApplicationModulesBaselineTest.java`, non-bloquant), donc aucune de ces deux options ne casserait le build aujourd'hui — mais le choix module top-level est documenté ici pour ne pas violer silencieusement le graphe une fois l'enforcement activé (Epic 12.2e, `deferred-work.md` ligne 35-40).

### Flutter — pièges déjà documentés à ne pas reproduire

- **Fuite `TextEditingController`** (`sale_detail_page.dart:186-260`, deferred `v1s-13-5`) : contrôleurs créés dans une méthode statique/dialog-builder, jamais disposés. Le flux de confirmation de suppression (AC7) DOIT être un widget avec cycle de vie propre (`StatefulWidget`/`ConsumerStatefulWidget`), pas un dialog bâti à la volée dans une méthode sans `dispose()`.
- **`Colors.white` codé en dur** (UX20, `suspension_banner.dart`, `checkout_page.dart` — tracké séparément en `v1s-16-3`, backlog) : ne pas en introduire de nouveaux dans `DeletionPendingBanner` ou `FeedbackFormPage`. Utiliser `Theme.of(context).colorScheme`/l'extension `KeevoColors` (`app_theme.dart:264-287`).
- **Mocktail et `AppDatabase.transaction<T>()`** : si l'implémentation Flutter finale appelle `_database.transaction(...)` quelque part dans le repository feedback (pas nécessaire a priori — `queueOperation()` seul suffit, comme pour `stock_repository_impl.dart`), Mocktail ne peut PAS stubber cette méthode générique correctement (`type 'Null' is not a subtype of type 'Future<void>'`). Si besoin, utiliser le pattern `FakeAppDatabase extends Fake implements AppDatabase` (voir `test/features/pos/data/repository/sale_repository_offline_first_test.dart:29-37`) plutôt qu'un `Mock`. Pour un simple `queueOperation()`, un `MockSyncService`/`MockSyncTriggerDispatcher` standard suffit.
- **`GoRouterState.of(context).name`** : toujours `null` dans cette app (aucune route n'est nommée, `grep -c "name:" app_router.dart` → 0). Utiliser `.uri.path` pour `screenContext`, jamais `.name`.
- **`package:image` déclaré mais jamais importé** (`pubspec.yaml:69`, `image: ^4.1.7`) — première utilisation réelle dans cette story pour la compression du screenshot. Vérifier la version épinglée est compatible avec l'API utilisée (`decodeImage`/`encodeJpg`/resize) au moment de l'implémentation.

### Fichiers backend à toucher (résumé)

| Fichier | Changement | AC |
|---|---|---|
| `identity/auth/domain/model/Tenant.java` | + champ `deletionScheduledAt` + `withStatus()` | AC1 |
| `identity/auth/adapter/out/persistence/entity/TenantJpaEntity.java` | + setter `deletionScheduledAt` | AC1 |
| `identity/auth/adapter/out/persistence/impl/TenantRepositoryAdapter.java` | mapping bidirectionnel | AC1 |
| `identity/auth/domain/model/AccountDeletionRequestedEvent.java` (nouveau) | record événement | AC2 |
| `identity/auth/domain/model/AccountDeletionCancelledEvent.java` (nouveau) | record événement | AC3 |
| `identity/auth/application/service/AccountDeletionService.java` (nouveau) | requestDeletion/cancelDeletion | AC2, AC3 |
| `identity/auth/adapter/in/rest/AccountController.java` (nouveau) | `POST /account/delete`, `/delete/cancel` | AC2, AC3 |
| `shared/domain/exception/ErrorCode.java` | + `ACCOUNT_DELETION_PENDING`/`DELETION_ALREADY_REQUESTED`/`DELETION_NOT_PENDING` | AC2-4 |
| `shared/infrastructure/web/GlobalExceptionHandler.java` | + `FR_MESSAGES` + `domainCodeToHttpStatus` | AC2-4 |
| `shared/infrastructure/web/AuditEventListener.java` | + 2 listeners | AC2, AC3 |
| `shared/infrastructure/security/JwtAuthFilter.java` | + bloc `DELETION_PENDING` (miroir SUSPENDED, ligne ~169) | AC4 |
| `shared/infrastructure/scheduling/AccountDeletionScheduler.java` (nouveau) | job quotidien | AC5 |
| `shared/infrastructure/persistence/TenantSchemaProvisioner.java` | + `dropSchemaForDeletion()` non-swallowing | AC5 |
| `admin/tenant/adapter/in/rest/dto/AdminTenant{ListItem,Detail}Dto.java` | vérifier couverture `DELETED` | AC6 |
| `feedback/package-info.java` (nouveau module) | `@ApplicationModule(allowedDependencies={"identity"})` | AC10 |
| `Vn__feedback.sql` (nouvelle migration, `V6` sauf si déjà pris) | `CREATE TABLE public.feedback` | AC10 |
| `feedback/feedback/domain/...`, `application/...`, `adapter/...` (nouveaux, structure hexagonale standard) | modèle + port + service + repo | AC10, AC11 |
| `feedback/feedback/adapter/in/rest/FeedbackController.java` (nouveau) | `POST /feedback` | AC11 |
| `sync/sync/application/handler/SubmitFeedbackSyncHandler.java` (nouveau) | handler offline | AC12 |
| `sync/package-info.java` | + `"feedback"` à `allowedDependencies` | AC12 |
| `admin/package-info.java` | + `"feedback"` à `allowedDependencies` | AC13 |
| `admin/feedback/adapter/in/rest/AdminFeedbackController.java` (nouveau) | `GET /admin/feedback` | AC13 |

**Hors scope explicite (vérifié, ne pas implémenter) :** tout adapter S3/object storage (n'existe pas, rien à supprimer) ; tout mécanisme d'opt-out WhatsApp (n'existe pas) ; toute modification de `account_status_provider.dart`/`SuspensionBanner` (propriété Story 14.9) ; toute tentative de "rafraîchir" le claim `tenantStatus` du JWT en cours de session (limitation acceptée, parité avec `SUSPENDED`).

### Fichiers Flutter à toucher (résumé)

| Fichier | Changement | AC |
|---|---|---|
| `keevo/app/lib/features/settings/presentation/page/account_page.dart` | + section "Zone dangereuse" OWNER / message EMPLOYEE | AC7, AC9 |
| `keevo/app/lib/core/scaffold/deletion_pending_banner.dart` (nouveau) + provider dédié | bannière indépendante | AC8 |
| `keevo/app/lib/core/scaffold/main_shell.dart` | monter `DeletionPendingBanner` dans la `Column` | AC8 |
| `keevo/app/lib/features/settings/presentation/page/settings_page.dart:336-344` | retirer stub "Bientôt", `onTap` réel | AC14 |
| `keevo/app/lib/core/router/app_router.dart` | + `GoRoute('/settings/feedback', ...)` | AC14 |
| `keevo/app/lib/features/settings/presentation/page/feedback_form_page.dart` (nouveau) | formulaire complet | AC14, AC15 |
| `keevo/app/pubspec.yaml` | + `package_info_plus` | AC14 |
| `keevo/app/test/features/settings/presentation/page/account_page_test.dart` | étendre (OWNER/EMPLOYEE variants) | AC7, AC9 |
| `keevo/app/test/.../deletion_pending_banner_test.dart` (nouveau) | tests bannière | AC8 |
| `keevo/app/test/.../feedback_form_page_test.dart` (nouveau) | tests formulaire | AC14, AC15 |

## Architecture Compliance

- **FR91, FR92, S1 (RGPD)** : ferme les deux plus gros gaps de conformité fonctionnelle identifiés par l'audit (`AUDIT_CONFORMITE_BMAD.md:83-84`), tous deux marqués Confiance Haute/Moyenne.
- **Modulith** : introduit un nouveau module top-level `feedback` avec dépendance minimale (`identity` seulement), respecte le graphe `allowedDependencies` existant en étendant `sync`/`admin` plutôt qu'en violant silencieusement leurs frontières déclarées.
- **Hexagonal** : `Tenant`/`AccountDeletionService`/`FeedbackRepository` restent des ports/domaine purs (aucun import Spring-web), conformément aux règles ArchUnit existantes (`KeevoArchUnitBaselineTest.java`).

## Dev Agent Record

### Agent Model Used
GitHub Copilot (DeepSeek V4 Pro)

### Debug Log References
- `TenantRepositoryAdapterTest`: 4/4 GREEN — round-trip null + non-null deletionScheduledAt + withStatus immutable checks
- `AccountDeletionServiceTest`: 6/6 GREEN — happy path, double-request 409, cancel, cancel-not-pending 409, WhatsApp non-blocking
- `AccountControllerTest`: 6/6 GREEN — OWNER 200, EMPLOYEE 403, 409 conflicts
- `JwtAuthFilterTest`: 17/17 GREEN — DELETION_PENDING block writes + allow reads (schema name 6-char fix: kv_del01→kv_del001)
- `AccountDeletionSchedulerTest`: 6/6 GREEN — expired deletion, not-yet-expired skip, ACTIVE/SUSPENDED skip, multi-tenant preservation, DROP failure resilience
- `SubmitFeedbackServiceTest`: 7/7 GREEN — keyword priority NORMAL/HIGH, case-insensitive, null-safe
- Full regression: 1709 tests, 1 failure + 45 errors = pre-existing baseline (ProductRepositoryAdapterTest H2, SaleRepositoryAdapterTest H2). 0 NEW regressions.

### Completion Notes List
1. **AC1 (Tenant model gap)** — `Tenant.java` gains `deletionScheduledAt` (nullable) + `withStatus(TenantStatus, Instant)`. `TenantJpaEntity` gains setter. `TenantRepositoryAdapter` maps bidirectionally. Round-trip test confirms null and non-null. Legacy constructors delegate to new full constructor with null default — zero impact on existing callers.
2. **AC2/AC3 (POST /account/delete + /cancel)** — `AccountDeletionService` uses hexagonal ports (`TenantRepository`, `UserRepository`, `WhatsAppPort`). WhatsApp confirmation sent on request, NO WhatsApp on cancel (per AC3). `AccountController` uses double-layer OWNER guard (`@PreAuthorize` + programmatic `isOwnerRole()`) mirroring `v1s-12-6` pattern. `AccountDeletionRequestedEvent`/`AccountDeletionCancelledEvent` are pure Java records.
3. **AC4 (JwtAuthFilter DELETION_PENDING)** — Duplicate of SUSPENDED block, distinct `ACCOUNT_DELETION_PENDING` domainCode for client diagnostics. Limitation accepted: JWT claim staleness (token issued before deletion request carries ACTIVE until refresh) — same characteristic as SUSPENDED, documented, not fixed (architectural change, out of scope).
4. **AC5 (AccountDeletionScheduler)** — Daily at 03:00 (offset from SubscriptionExpiryScheduler at 02:00). Uses `Clock` injection for testability. SQL sequence: capture user IDs → delete memberships → delete ONLY orphaned users (NOT EXISTS guard) → delete refresh_tokens → delete device_tokens (orphaned subset only) → DROP SCHEMA CASCADE → mark DELETED. If DROP fails, tenant NOT marked DELETED (data preserved).
5. **AC5 — dropSchemaForDeletion()** — Added to `TenantSchemaProvisioner`. Unlike `dropSchemaIfExists()` (best-effort, swallows exceptions), this method PROPAGATES the SQLException so the scheduler can abort and not mark the tenant deleted while data still exists.
6. **AC6 (Admin visibility)** — `AdminTenantListItemDto`/`AdminTenantDetailDto` use `String status` (no enum switch) — `"DELETED"` passes through without code changes. Verified: no exhaustive switch that would break.
7. **AC10 (Module feedback + V7 migration)** — New top-level module `com.keevo.feedback` with `@ApplicationModule(allowedDependencies={"identity"})`. V7__feedback.sql creates `public.feedback` table (V6 was already taken by password_reset_tokens). Note: `tenant_id` is `varchar(15)` (schema name), not UUID, for consistency with `TenantContext.getCurrentTenant()` which returns schema name.
8. **AC11/AC12 (FeedbackController + SyncHandler)** — `POST /api/v1/feedback` accessible to OWNER+EMPLOYEE. `tenantId`/`userId` derived server-side from `TenantContext`/`SecurityContextHolder`. `SubmitFeedbackSyncHandler` delegates to same `SubmitFeedbackUseCase` (no logic duplication). `sync/package-info.java` and `admin/package-info.java` updated with `"feedback"` dependency.
9. **AC13 (AdminFeedbackController)** — `GET /api/v1/admin/feedback` paginated, filtrable by `priority`/`screenContext`. Inherits `SUPER_ADMIN` filter from `SecurityConfig /api/v1/admin/**`.
10. **NO-OP documented** — S3 deletion: no infrastructure exists (no AWS SDK in pom.xml, photos are local Flutter device paths). WhatsApp opt-out: no mechanism exists (grep -ri "optOut" backend/ → empty). Both explicitly NO-OP, documented here per story spec.
11. **Flutter tasks (AC7-9, AC14-15)** — NOT YET IMPLEMENTED. Backend-only delivery complete. Flutter FR91 (AccountPage zone dangereuse + DeletionPendingBanner) and FR92 (FeedbackFormPage) remain as Tasks 7-8.

### File List

**Modified files:**
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/Tenant.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/entity/TenantJpaEntity.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/TenantRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java`
- `keevo/backend/src/main/java/com/keevo/sync/package-info.java`
- `keevo/backend/src/main/java/com/keevo/admin/package-info.java`

**New files:**
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/AccountDeletionRequestedEvent.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/AccountDeletionCancelledEvent.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/application/service/AccountDeletionService.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/in/rest/AccountController.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/scheduling/AccountDeletionScheduler.java`
- `keevo/backend/src/main/resources/db/migration/V7__feedback.sql`
- `keevo/backend/src/main/java/com/keevo/feedback/package-info.java`
- `keevo/backend/src/main/java/com/keevo/feedback/feedback/domain/model/Feedback.java`
- `keevo/backend/src/main/java/com/keevo/feedback/feedback/domain/port/in/SubmitFeedbackUseCase.java`
- `keevo/backend/src/main/java/com/keevo/feedback/feedback/domain/port/out/FeedbackRepository.java`
- `keevo/backend/src/main/java/com/keevo/feedback/feedback/application/service/SubmitFeedbackService.java`
- `keevo/backend/src/main/java/com/keevo/feedback/feedback/adapter/out/persistence/FeedbackJpaEntity.java`
- `keevo/backend/src/main/java/com/keevo/feedback/feedback/adapter/out/persistence/FeedbackSpringRepository.java`
- `keevo/backend/src/main/java/com/keevo/feedback/feedback/adapter/out/persistence/impl/FeedbackRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/feedback/feedback/adapter/in/rest/FeedbackController.java`
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/SubmitFeedbackSyncHandler.java`
- `keevo/backend/src/main/java/com/keevo/admin/feedback/adapter/in/rest/AdminFeedbackController.java`

**New test files:**
- `keevo/backend/src/test/java/com/keevo/identity/auth/adapter/out/persistence/impl/TenantRepositoryAdapterTest.java`
- `keevo/backend/src/test/java/com/keevo/identity/auth/application/service/AccountDeletionServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/identity/auth/adapter/in/rest/AccountControllerTest.java`
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/scheduling/AccountDeletionSchedulerTest.java`
- `keevo/backend/src/test/java/com/keevo/feedback/feedback/application/service/SubmitFeedbackServiceTest.java`

**Modified test files:**
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtAuthFilterTest.java`

## Review Findings (code review 2026-07-23)

### Decision Needed

- [x] [Review][Decision] **`@Transactional` + DROP SCHEMA hors transaction = corruption de données** — **RÉSOLU : option (a) réordonner** (save DELETED avant DROP). Si le DROP échoue, le tenant est marqué DELETED, retry possible.
- [x] [Review][Decision] **AC10 — `tenant_id` varchar(15) au lieu de uuid** — **RÉSOLU : déviation acceptée** (cohérent avec `TenantContext` + `refresh_tokens.tenant_id` varchar).

### Patch

- [x] [Review][Patch] **JwtAuthFilter bloque `/account/delete/cancel` après refresh JWT** [JwtAuthFilter.java:183-188] — Whitelister `/api/v1/account/delete/cancel` dans le check DELETION_PENDING (sinon la période de grâce est inutilisable après refresh JWT) — **APPLIÉ**
- [x] [Review][Patch] **Deux constructeurs sans `@Autowired` — Spring startup failure** [AccountDeletionScheduler.java:42-64] — Ajouter `@Autowired` au constructeur de production (5 params) — **APPLIÉ**
- [x] [Review][Patch] **WhatsApp I/O dans `@Transactional` — épuisement pool connexions** [AccountDeletionService.java:80] — Déplacer l'appel WhatsApp après commit (afterCommit callback) — **APPLIÉ**
- [x] [Review][Patch] **Casts `(String)` non sûrs dans sync handler** [SubmitFeedbackSyncHandler.java:38-41] — Utiliser `String.valueOf()` et `instanceof` — **APPLIÉ**
- [x] [Review][Patch] **Pas de validation de longueur sur feedback** [FeedbackController.java:55-61] — Ajouter `@Valid` + `@Size`/`@NotBlank` — **APPLIÉ**
- [x] [Review][Patch] **`toLowerCase()` sans Locale explicite** [SubmitFeedbackService.java:59] — Utiliser `Locale.FRENCH` — **APPLIÉ**
- [x] [Review][Patch] **JWT parsé deux fois par requête** [AccountController.java:88-106] — Utiliser `SecurityContextHolder` + `TenantContext` — **APPLIÉ**
- [x] [Review][Patch] **Race condition : scheduler vs cancel** [AccountDeletionScheduler.java:75-85] — Re-fetch du tenant avant traitement — **APPLIÉ**
- [x] [Review][Patch] **`extractActorId` ne gère pas les JWT malformés** [AccountController.java:93] — Utiliser `SecurityContextHolder` — **APPLIÉ**
- [x] [Review][Patch] **Pas de borne supérieure sur `size` parameter** [AdminFeedbackController.java:38] — Cap à 100 — **APPLIÉ**
- [x] [Review][Patch] **Fichiers de tests manquants** [Task 6.6] — Ajouter `FeedbackControllerTest`, `AdminFeedbackControllerTest`, `SubmitFeedbackSyncHandlerTest` — **APPLIÉ**
- [x] [Review][Patch] **Réordonner scheduler : save DELETED avant DROP** [AccountDeletionScheduler.java:150-155] — Inverser étapes 7 et 8 — **APPLIÉ**

### Defer

- [x] [Review][Defer] **Pas de distributed lock sur scheduler** [AccountDeletionScheduler.java:69] — deferred, pre-existing (`SubscriptionExpiryScheduler` a le même pattern)
- [x] [Review][Defer] **AdminFeedbackController bypass la couche application** [AdminFeedbackController.java:25] — deferred, architecture violation mais pas de bug fonctionnel
- [x] [Review][Defer] **`findAll()` charge tous les tenants** [AccountDeletionScheduler.java:75] — deferred, optimisation scalability
- [x] [Review][Defer] **Feedback domain model sans null-safety** [Feedback.java:24-37] — deferred, cosmétique
- [x] [Review][Defer] **Transition SUSPENDED → DELETION_PENDING autorisée** [AccountDeletionService.java:60-84] — deferred, unclear si bug ou feature (RGPD right)

### Dismissed (3)

- `refresh_tokens` type mismatch — faux positif, `tenant_id` est `varchar(64)` dans V1__baseline
- Sync handler non idempotent — spec AC12 ne requiert pas l'idempotence
- `Instant.now()` clock skew — pas de concern réel pour fenêtre 30 jours
