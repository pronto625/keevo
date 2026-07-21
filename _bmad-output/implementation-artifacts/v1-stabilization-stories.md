# V1 Stabilization — Index ordonné des stories

> **Branche :** `v1-stabilization` (base `deploy` d766035 = structure V1 pré-refonte)
> **Tracker :** `sprint-status.yaml` (clés `v1s-*`) · **Backlog détaillé :** `epics/epics-remediation-audit.md` · **Audit :** `../../AUDIT_CONFORMITE_BMAD.md`
> **Convention statut :** `[ ]` = backlog · `[~]` = ready-for-dev/in-progress · `[x]` = done
> **Tags :** A = patch V1 now · C = patch V1 + refonte ré-applique · B = refonte-native (PAS sur v1-stab)

**Progression : 0 / 31 stories V1 (tag A+C)** — Sprint 1 (22) · Sprint 2 (6) · Continu (2) · tag B refonte (7, hors v1-stab)

---

## Sprint 1 — Tag A — Release-blockers V1 (à faire en premier)

### Sécurité & Hardening (Epic 12)

- [ ] **`v1s-12-1`** chmod clé JWT privée → `chmod 600` · `deploy-backend.yml:112` · S1 · *1 ligne, quick win*
- [ ] **`v1s-12-3`** `SecurityConfig` admin path `hasRole("SUPER_ADMIN")` · S3
- [ ] **`v1s-12-5`** `TenantSchema.validate(tenantId)` dans `JwtAuthFilter` · S5/B-HIGH-6
- [ ] **`v1s-12-2`** Révocation session <5min (V4 `tokens_valid_after` + `TokenRevocationPort`) · S2/NFR12 · ⚠ *dépendance pour 14.9 & 14.12*
- [ ] **`v1s-12-6`** RBAC owner-only + scope store EMPLOYEE · S6/S7/FR36 · *+ contrepartie mobile UI gating*
- [ ] **`v1s-12-7`** Hardening Flutter (SecureStorageProvider + redact Bearer + HTTPS) · S8/S9/S12
- [ ] **`v1s-12-4`** Dashboard Next.js : `proxy.ts` → `middleware.ts` · S4

### Concurrence & Intégrité (Epic 13) — les plus critiques

- [ ] **`v1s-13-1`** `@Version` StockTransfer + StockLevel · B-CRIT-1/2 · *+ mobile : gestion 409/silent refresh*
- [ ] **`v1s-13-2`** Porte sync 7j déverrouillable (pull lève la porte + deviceId lié actor) · B-CRIT-3/FR74
- [ ] **`v1s-13-3`** DayClosureDeltaProvider + fenêtre hebdo lundi + timing EOD · B-MED/FR42-53
- [ ] **`v1s-13-4`** sync_queue dans la txn Drift (vente atomique) · F-HIGH-4 · *Flutter*
- [ ] **`v1s-13-5`** FR90 annulation vente complétée + restauration stock · B-HIGH-8/F-CRIT-1 · *refonte-native = 17-1*
- [ ] **`v1s-13-6`** Statut draft = PENDING_VALIDATION (record_sale_notifier) · F-CRIT-2 · *Flutter*
- [ ] **`v1s-13-7`** Audit/mouvement sur validation inventaire offline · F-CRIT-3/FR48 · *Flutter*

### Cassures API + Notif (Epic 14, tag A)

- [ ] **`v1s-14-8`** Cassures API : `/unarchive` + `dest`→`destination` + `ReportsDeltaProvider` + `api_service` envelope · X-CRIT/HIGH/MED
- [ ] **`v1s-14-9`** FR20 bannières trial/suspension + branche session-révoquée · F-HIGH-5/8 · ⚠ *dépend 12.2/12.7*
- [~] **`v1s-14-10`** Notif owner sur actions employé (produit + stock) · spec `spec-notif-owner-actions-employe.md` · *+ mobile FCM réception*

### Mobile Hardening & Parity (Epic 16) — 100% Flutter

- [ ] **`v1s-16-1`** Cleanup token FCM au logout · 8-0 AC10
- [ ] **`v1s-16-2`** Notifications iOS Darwin + foreground display · 8-0 AC4
- [ ] **`v1s-16-3`** Dark mode (41 `Colors.white` → surface) + contraste bannière offline · UX20
- [ ] **`v1s-16-4`** Recherche catalogue debounce 300ms + fuzzy + catégorie · UX9
- [ ] **`v1s-16-5`** SyncIndicator online-stale + daysSinceLastSync · UX15/5-4 AC1
- [ ] **`v1s-16-6`** Typo `STOCK_ADJUST`→`ADJUSTMENT` + store offline sync_queue · FR27/FR30 · *ties 13.4*

---

## Sprint 2 — Tag C — Scope V1 promis

- [ ] **`v1s-14-2`** FR44 leaderboard vendeurs · *refonte-native = 17-2*
- [ ] **`v1s-14-5`** FR91 suppression compte (RGPD) + FR92 feedback · *refonte-native = 17-4 + 16-6*
- [ ] **`v1s-14-6`** FR7 tutoriels vidéo + FR65 invite WhatsApp + FR67 activité employé · *17-3 couvre FR67*
- [ ] **`v1s-14-7`** FR55 export PDF/Excel + plan-gating · *refonte-native = 17-6*
- [~] **`v1s-14-11`** Édition complète employé (nom/tel/password/role) · spec `spec-edition-complete-employe.md` · FR66
- [~] **`v1s-14-12`** Mot de passe oublié OTP WhatsApp · spec `spec-mot-de-passe-oublie.md` · FR3b · ⚠ *dépend 12.2*

---

## Continu — Tag C — Hygiène

- [ ] **`v1s-15-3`** Tests RED manquants (CategoryController, CompleteTransferService, adapters WhatsApp, stratégies rentabilité)
- [ ] **`v1s-15-5`** Réconcilier deferred-work + code mort (`UserSyncStateDdlInitializer`, `common`/`products`, TODOs)

---

## Refonte-native — Tag B — PAS sur v1-stab (restent sur `refractoring` / Epic 17)

*Listés pour mémoire — à faire sur la refonte, pas sur cette branche.*

- 14.1 FR22 variantes produit · *gap refonte*
- 14.3 FR79 dashboard revenus (MRR/churn) · *gap refonte*
- 14.4 FR82/83 push global + santé système · *= 17-5*
- 15.1 Base Testcontainers PostgreSQL (ARCH12)
- 15.2 Flutter integration_test E2E (ARCH13)
- 15.4 ArchUnit/Modulith mode ratchet (ARCH5/7/10)
- 15.6 Enveloppe pagination ARCH28 `meta{page,total}`

---

## Ordre recommandé de démarrage

1. **`v1s-12-1`** (1 ligne) → valide le setup CI/deploy
2. **`v1s-12-3`** + **`v1s-12-5`** (sécurité backend, rapides)
3. **`v1s-12-2`** (révocation — débloque 14.9/14.12)
4. **`v1s-13-1`** + **`v1s-13-2`** (concurrence critique — le plus important pour l'intégrité V1)
5. **`v1s-14-8`** (cassures runtime) + **`v1s-14-10`** (spec prête)
6. **`v1s-16-1..6`** (mobile, indépendant)
7. Sprint 2 (scope) puis Continu

## Pointeurs rapides

- **Backlog complet (ACs + TDD tasks) :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md` (story `14.10`, `14.11`, etc.)
- **Audit (preuves file:line) :** `AUDIT_CONFORMITE_BMAD.md`
- **Tracker statuses :** `_bmad-output/implementation-artifacts/sprint-status.yaml` (clés `v1s-*`)
- **Reprendre la refonte :** `git checkout refractoring` (WIP snapshotté en `ae81a6c`)
