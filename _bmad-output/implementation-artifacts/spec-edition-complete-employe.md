# Spec — Édition complète d'un employé par le OWNER (numéro, nom, mot de passe, rôle, …)

> **Statut :** SPÉCIFICATION AVANT-CODE (à valider avant implémentation). Aucun code modifié.
> **Date :** 2026-07-20
> **Alignement :** BMAD — étend FR66, Story 3.5 (invitation employés/roles), Story 8.6 (changement mot de passe). S'ajoute au backlog de remédiation en Story 14.11 (piste C).

---

## 1. Contexte & motivation

Le OWNER peut aujourd'hui **créer** un employé, **réassigner** sa boutique, **désactiver/réactiver** son accès, et **régénérer** un mot de passe temporaire (`EmployeeController`). Mais il **ne peut pas éditer** les informations d'un employé existant :

- ❌ Modifier le **prénom / nom**
- ❌ Modifier le **numéro de téléphone** (identifiant de login + numéro WhatsApp)
- ❌ Modifier le **rôle** (EMPLOYEE ↔ OWNER)
- ❌ **Set/choisir** le mot de passe (seule la régénération aléatoire existe)

**Objectif :** le OWNER peut modifier **toutes** les informations d'un employé — prénom, nom, numéro, mot de passe, rôle, boutique, statut — depuis une seule édition cohérente, avec les contraintes de sécurité qui vont avec (phone = login id, rôle/password impliquent révocation de session, audit immuable).

---

## 2. Décisions produit (proposées — à valider §9)

| # | Décision | Recommandation |
|---|---|---|
| E1 | Edition du numéro de téléphone | **Modifiable** ; re-valide unicité (tableau `public.users`, cross-tenant car login id global) + format E.164 via `IntlPhoneField` côté client. |
| E2 | Edition du prénom/nom | **Modifiable** librement (champs `kv_xxx.employees`). |
| E3 | Edition du rôle | **Modifiable** EMPLOYEE ↔ OWNER. Interdit de **se rétrograder soi-même** et de **rétrograder le dernier OWNER actif** (verrou anti-lockout). |
| E4 | Mot de passe défini par le owner | Owner **set un nouveau mot de passe** + force `passwordChangeRequired = true` (l'employé doit le re-changer à la prochaine connexion — sécurité, car le owner a vu le mot de passe). Alternative : owner génère un temporaire (existant). |
| E5 | Révocation de session sur rôle/password modifiés | **Oui** — changement de rôle ou de mot de passe → `revokeAllSessions(userId, tenantId)` (ties Story 12.2). |
| E6 | Statut (ACTIVE/INACTIVE) | Conservé via les endpoints `deactivate`/`reactivate` existants (pas dupliqué dans l'édition). |

---

## 3. Modèle de données impliqué (3 agrégats)

| Agrégat | Table | Champs éditables via cette spec |
|---|---|---|
| `Employee` | `kv_xxx.employees` | `firstName`, `lastName`, `storeId` (déjà via `/store`), `passwordChangeRequired` (reset sur set-password) |
| `User` | `public.users` | `phoneNumber`, `passwordHash` |
| `UserTenantMembership` | `public.user_tenant_memberships` | `role` |

> L'édition est **transactionnelle** et cohérente sur les 3 agrégats via un use case unifié `UpdateEmployeeUseCase` (hexagonal). La validation d'unicité du téléphone est cross-tenant (`public.users`).

---

## 4. API — endpoints

### 4.1 `PATCH /api/v1/employees/{employeeId}` — édition profil (nom, téléphone, boutique)
- **OWNER-only** (`requireOwnerOrForbid()`).
- Body (tous optionnels, partial update) :
  ```json
  { "firstName": "Loïc", "lastName": "Diallo", "phoneNumber": "+2376xxxxxxx", "storeId": "<uuid>" }
  ```
- Effets :
  - firstName/lastName → `kv_xxx.employees`
  - phoneNumber → `public.users` (re-valide unicité + format ; si pris par un autre user → 409 `PHONE_ALREADY_REGISTERED`)
  - storeId → `kv_xxx.employees` (remplace `/store` dédié ou l'inclut — **décision §9**)
- Events émis : `EmployeeUpdatedEvent` (portant `actorId`, `actorRole`, `fieldsChanged[]`, `tenantId`) → audit immuable.

### 4.2 `PATCH /api/v1/employees/{employeeId}/role` — changement de rôle
- **OWNER-only**.
- Body : `{ "role": "OWNER" | "EMPLOYEE" }`
- Effets :
  - Update `public.user_tenant_memberships.role`
  - Contrôles : interdit si `employeeId == actorId` (self-demotion) → 403 `CANNOT_CHANGE_OWN_ROLE` ; interdit si c'est le dernier OWNER actif du tenant et qu'on le rétrograde → 403 `CANNOT_DEMOTE_LAST_OWNER`
  - `revokeAllSessions(userId, tenantId)` (ties 12.2) — privileges changed
  - Event : `EmployeeRoleChangedEvent` (previousRole, newRole, actorId) → audit.

### 4.3 `POST /api/v1/employees/{employeeId}/password` — set mot de passe par le owner
- **OWNER-only**.
- Body : `{ "newPassword": "<chosen>" }` (min ≥8, ≥1 chiffre, conformément AC4 Story 3-5)
- Effets :
  - `passwordHash` mis à jour (bcrypt cost 12) sur `public.users`
  - `passwordChangeRequired = true` sur `kv_xxx.employees` (l'employé doit re-changer — E4)
  - `revokeAllSessions(userId, tenantId)` (ties 12.2) — anciens tokens invalidés
  - Event : `EmployeePasswordSetEvent` (actorId, employeeId, forcedReset=true) → audit
- Alternative conservée : `POST /employees/{id}/regenerate-password` (temp aléatoire, existant) — non cassé.

---

## 5. Contraintes de sécurité & invariants

1. **Phone = login id** : changer le téléphone change l'identifiant de connexion. Unicité cross-tenant `public.users`. Le client `IntlPhoneField` valide le format. ⚠ Pas de confirmation OTP en V1 (hors scope — §9).
2. **Rôle** : EMPLOYEE↔OWNER. Verrous anti-lockout (E3). Un OWNER promu obtient accès au dashboard/rentabilité/etc. — d'où la révocation de session (re-issue un JWT avec le nouveau rôle à la prochaine connexion).
3. **Mot de passe owner-set** : toujours `passwordChangeRequired=true` (jamais persisté en clair ; le owner voit le hash a été set, pas le clair — le client n'affiche le mot de passe que si le owner l'a tapé). Best-effort, mais la révocation de session est synchrone.
4. **Audit immuable** : chaque édition (profil, rôle, password) émet un event → `AuditEventListener` → `audit_log` append-only (FR84, NFR13). Inclut `before/after` values.
5. **RBAC** : OWNER-only sur les 3 endpoints ; EMPLOYEE → 403.
6. **Plan limit** : la promotion EMPLOYEE→OWNER ne **crée pas** de nouvel employé (pas de `PlanLimitGuard` sur le role-change). Le `PlanLimitGuard` reste sur la **création** (AC2 Story 3-5).

---

## 6. UI Flutter

- `EmployeeCard` / page détail employé : bouton **« Modifier »** → ouvre un formulaire d'édition pré-rempli (prénom, nom, téléphone `IntlPhoneField`, boutique dropdown, rôle dropdown OWNER/EMPLOYEE).
- Section **« Sécurité »** : tile « Définir le mot de passe » (champs nouveau mot de passe + confirmation, validation min ≥8/≥1 chiffre) + tile « Régénérer un mot de passe temporaire » (existant).
- Confirmation dialog pour le changement de rôle (avertissement « Cet employé sera déconnecté et devra se reconnecter ») et pour le set-password.
- Le owner ne voit pas son propre rôle comme éditable (self-edit verrouillé côté UI aussi).

---

## 7. Tests TDD (ARCH11/14 — RED d'abord)

### `UpdateEmployeeUseCaseTest`
- `shouldUpdateFirstNameLastName()`
- `shouldUpdatePhoneNumberWithUniquenessCheck()` (409 si pris)
- `shouldRejectInvalidPhoneFormat()`
- `shouldUpdateStoreId()`
- `shouldEmitEmployeeUpdatedEventWithChangedFields()`
- `shouldAuditLogAllChanges()`

### `ChangeEmployeeRoleUseCaseTest`
- `shouldPromoteEmployeeToOwner()`
- `shouldDemoteOwnerToEmployee()`
- `shouldRejectSelfDemotion()` (403 `CANNOT_CHANGE_OWN_ROLE`)
- `shouldRejectDemoteLastActiveOwner()` (403 `CANNOT_DEMOTE_LAST_OWNER`)
- `shouldRevokeAllSessionsOnRoleChange()`
- `shouldEmitEmployeeRoleChangedEvent()`

### `SetEmployeePasswordUseCaseTest`
- `shouldSetNewPasswordHashWithBcrypt12()`
- `shouldForcePasswordChangeRequiredTrue()`
- `shouldRejectWeakPassword()` (<8 ou pas de chiffre)
- `shouldRevokeAllSessionsOnPasswordSet()`
- `shouldEmitEmployeePasswordSetEvent()`

### Controller slice (`@WebMvcTest`)
- `shouldReturn403ForEmployeeOnAllThreeEndpoints()`
- `shouldReturn409OnDuplicatePhone()`

---

## 8. Mapping des artefacts BMAD à mettre à jour

### 8.1 `requirements-inventory.md`
- **FR66 (éditer)** : « Un propriétaire peut gérer les rôles de ses utilisateurs (Propriétaire / Employé) **et modifier toutes les informations d'un employé (prénom, nom, numéro de téléphone, mot de passe, rôle, boutique)**. »

### 8.2 Story 3.5 (`3-5-invitation-employes-...`)
- **Nouvel AC — AC8 : Édition complète d'un employé** (PATCH `/employees/{id}` profile + `/role` + `/password`), avec les verrous anti-lockout, la révocation de session sur rôle/password, et l'audit. Cross-ref cette spec.

### 8.3 Story 8.6 (`8-6-changement-mot-de-passe-...`)
- Préciser que le self-service `POST /auth/change-password` reste pour l'utilisateur lui-même ; le set-password par le owner est un endpoint séparé (`POST /employees/{id}/password`) qui force `passwordChangeRequired=true`.

### 8.4 Backlog remédiation (`epics-remediation-audit.md`)
- Ajouter **Story 14.11** (piste C — V1-shippable + refonte ré-applique) pointant vers cette spec.

---

## 9. Décisions à valider (checklist PO)

- [ ] E1 : téléphone modifiable sans confirmation OTP en V1 ? (recommandé : oui, unicité suffit)
- [ ] E3 : autoriser la promotion EMPLOYEE→OWNER (co-propriétariat) ? Verrou dernier-OWNER OK ?
- [ ] E4 : owner-set password force-t-il `passwordChangeRequired=true` ? (recommandé : oui)
- [ ] 4.1 : fusionner le réassign-store (`/store`) dans le PATCH profil unifié, ou garder 2 endpoints ? (recommandé : PATCH unifié + garder `/store` pour rétrocompat)
- [ ] E5 : révocation de session synchrone sur rôle/password (ties Story 12.2) — confirmer la dépendance 12.2 d'abord.
- [ ] Décider si le set-password affiche le mot de passe en clair au owner (one-time) ou non (recommandé : non, le owner l'a tapé).

---

## 10. Hors-scope (explicite)

- Confirmation OTP/WhatsApp du changement de téléphone (V2).
- Photo de profil employé (V2).
- Historique des modifications consultable en UI (l'audit_log est la source de vérité, pas un écran dédié en V1).
- MFA avant édition sensible (V2).
- Édition d'un employé par un autre OWNER non-créateur (tous les OWNER du tenant ont les mêmes droits — confirmé par le modèle membership).
