# Spec — Réinitialisation mot de passe oublié (OWNER + EMPLOYEE) via OTP WhatsApp

> **Statut :** SPÉCIFICATION AVANT-CODE (à valider avant implémentation). Aucun code modifié.
> **Date :** 2026-07-20
> **Alignement :** BMAD — nouvelle exigence (proposition FR3b), étend Story 1.3 (auth/session). S'ajoute au backlog de remédiation en Story 14.12 (piste C). Dépend de Story 12.2 (révocation de session).

---

## 1. Contexte & motivation

Le login Keevo est **phone + password** (FR1 : inscription via numéro WhatsApp, pas d'email). Aujourd'hui :

- ❌ Aucun flow « mot de passe oublié ».
- `POST /api/v1/auth/change-password` exige le `currentPassword` → inutilisable si oublié.
- Le OWNER peut reset le mot de passe d'un **employé** via Story 14.11 (`POST /employees/{id}/password`) — mais ça ne couvre pas :
  - le **OWNER lui-même** qui a oublié son mot de passe (pas de self-service),
  - l'employé quand le owner n'est pas disponible.

**Objectif :** un **self-service de reset via OTP WhatsApp** pour OWNER **et** EMPLOYEE — l'utilisateur entre son numéro, reçoit un code à usage unique via WhatsApp, et définit un nouveau mot de passe. Cohérent avec l'identité WhatsApp-first de Keevo (FR1, FR57–60, ARCH23).

---

## 2. Décisions produit (proposées — à valider §9)

| # | Décision | Recommandation |
|---|---|---|
| R1 | Canal de reset | **WhatsApp OTP** uniquement (pas d'email/SMS en V1) — cohérent FR1. |
| R2 | OTP format & TTL | 6 chiffres, **TTL 10 min**, **à usage unique**, hashé au repos (bcrypt ou HMAC). |
| R3 | Rate limiting | Request : 1 code/min, 5/hour par téléphone. Verify : max 5 tentatives puis verrou du token. (FR93 / anti-abus.) |
| R4 | Protection énumération d'utilisateurs | `POST /forgot-password` retourne **toujours 200** (même si le téléphone n'existe pas) ; le code n'est envoyé que si l'utilisateur existe. |
| R5 | `passwordChangeRequired` après reset | **false** — l'utilisateur vient de définir son mot de passe. |
| R6 | Révocation de session au reset | **Oui** — `revokeAllSessions(userId, tenantId)` sur toutes les memberships de l'utilisateur (ties Story 12.2). |
| R7 | Fallback SUPER_ADMIN (support) | **Optionnel V2** — `POST /api/v1/admin/users/{id}/reset-password` (SUPER_ADMIN) pour le cas où le owner a perdu son téléphone. Pas en V1. |

---

## 3. Modèle de données

- Nouvelle table **`public.password_reset_tokens`** (schéma public, cross-tenant car le téléphone identifie un user global) :
  - `id` UUID PK
  - `user_id` UUID (FK `public.users.id`)
  - `phone_number` VARCHAR (index, pour lookup + rate-limit)
  - `code_hash` VARCHAR (hash du code OTP — jamais le clair au repos)
  - `expires_at` TIMESTAMP
  - `consumed_at` TIMESTAMP NULL
  - `attempts` INT DEFAULT 0
  - `created_at` TIMESTAMP
  - Index : `(phone_number, created_at)`, `user_id`.
- **Pas de migration Flyway tenant** — table publique (le téléphone est global).
- Le code OTP en clair n'est **jamais persisté** ; le hash est comparé à la vérification.

---

## 4. API — endpoints (publics, pas de JWT)

### 4.1 `POST /api/v1/auth/forgot-password`
- **Public** (pas d'auth — accessible depuis l'écran de login « Mot de passe oublié ? »).
- Body : `{ "phoneNumber": "+2376xxxxxxx" }`
- Effets :
  - Lookup `public.users` par téléphone. Si absent → **réponse 200 quand même** (R4), aucun envoi.
  - Si présent : génère un code 6 chiffres (`SecureRandom`), hash, persiste `password_reset_tokens` (invalide les tokens non consommés précédents pour ce user), envoie via `WhatsAppPort.sendOtp(phoneNumber, code)`.
  - Rate-limit : 1/min, 5/hour par téléphone (R3) → 429 `RATE_LIMIT_EXCEEDED` si dépassé (mais ne **pas** révéler que c'est rate-limit vs absent — pour R4, idéalement toujours 200 et juste ne pas envoyer ; **décision §9**).
- Réponse : `{ "sent": true }` (toujours, pour R4).
- Event : `PasswordResetRequestedEvent` (userId si existe, phoneNumber, tenantId null car cross-tenant) → audit (best-effort, sans révéler l'existence).

### 4.2 `POST /api/v1/auth/reset-password`
- **Public**.
- Body : `{ "phoneNumber": "...", "code": "123456", "newPassword": "..." }`
- Effets :
  - Valide le code : lookup le token non-consommé non-expiré par téléphone, compare `code_hash` (bcrypt/HMAC), incrémente `attempts` à chaque échec → si `attempts >= 5` → token verrouillé (R3).
  - Si valide : `passwordHash` mis à jour sur `public.users` (bcrypt cost 12), `consumed_at = NOW()`, `passwordChangeRequired = false` sur l'employee record concerné (si employé), `revokeAllSessions(userId, ...)` sur **toutes** les memberships (R6).
  - Nouveaux tokens **non** émis ici — l'utilisateur doit se logger normalement (login → select-tenant). Le reset ne connecte pas.
  - Event : `PasswordResetEvent` (userId, actorId=null/self, occurredAt) → audit immuable (FR84).
- Réponses : 200 `{ "reset": true }` ; 400/422 `INVALID_OR_EXPIRED_CODE` / `CODE_LOCKED` / weak password `JUSTIFICATION_TOO_SHORT` (réutiliser le validateur min ≥8/≥1 chiffre de Story 3-5 AC4).

### 4.3 `WhatsAppPort` — extension
- Ajouter `void sendOtp(String phoneNumber, String code)` (ou générique `void send(String phoneNumber, String message)` dont l'OTP est un cas). Message FR : «Votre code de réinitialisation Keevo est {code}. Il expire dans 10 minutes. Si vous n'avez pas demandé, ignorez ce message.»

---

## 5. Flow multi-tenant & multi-membership

- Un téléphone → un `public.users` → **N** `user_tenant_memberships` (un user peut appartenir à plusieurs tenants, FR1.7 two-step login).
- Le reset met à jour le `passwordHash` **global** (`public.users`) → affecte **toutes** les memberships.
- Après reset, le user se connecte via le 2-step existant (phone+password → liste memberships → select-tenant). Pas de changement du flow de login.
- `revokeAllSessions` doit boucler sur **toutes** les memberships du user (pas seulement une) — sinon un token sur un autre tenant reste valide.

---

## 6. Contraintes de sécurité & invariants

1. **OTP jamais en clair au repos** — hashé. Le clair transite seulement via WhatsApp (TLS, NFR7).
2. **Single-use + TTL 10 min** — un token consommé ne peut pas être rejoué ; un token expiré est rejeté.
3. **Anti-énumération** — `forgot-password` toujours 200 ; `reset-password` ne révèle pas si le téléphone existe (erreurs génériques `INVALID_OR_EXPIRED_CODE`).
4. **Rate-limit request + verify** — R3. Verrou du token après 5 tentatives.
5. **Révocation de session** — R6, synchrone, ties 12.2 (le `tokens_valid_after` est setté sur toutes les memberships).
6. **Audit immuable** — `PasswordResetRequestedEvent` + `PasswordResetEvent` → `audit_log` append-only (FR84/NFR13). Le `requested` event ne log **pas** les téléphones inexistants (anti-énumération).
7. **Pas de révélation du téléphone** dans les logs applicatifs (MDC/structuré — le téléphone est PII ; logger seulement le `userId` + un hash du téléphone).
8. **RBAC** — les deux endpoints sont **publics** (pas de JWT). C'est voulu (l'utilisateur n'est pas connecté).

---

## 7. UI Flutter

- Écran de login : lien **« Mot de passe oublié ? »** sous le bouton de connexion.
- Page `/auth/forgot-password` :
  - `IntlPhoneField` (numéro, défaut CM) + bouton « Envoyer le code ».
  - SnackBar : « Si ce numéro existe, un code WhatsApp vous a été envoyé. » (ne révèle rien).
  - Rate-limit côté client (désactive le bouton 60s après envoi).
- Page `/auth/reset-password` :
  - Code OTP (6 chiffres, auto-focus, inputs séparés) + nouveau mot de passe + confirmation.
  - Validation min ≥8/≥1 chiffre (même validateur que Story 3-5 AC4).
  - Bouton « Réinitialiser » → succès → redirect `/auth/login` avec SnackBar « Mot de passe réinitialisé, connectez-vous. »
  - Erreurs : code invalide/expiré, code verrouillé, mot de passe faible — messages humains (UX18).

---

## 8. Tests TDD (ARCH11/14 — RED d'abord)

### `RequestPasswordResetUseCaseTest`
- `shouldGenerateAndSendOtpWhenUserExists()`
- `shouldReturn200AndNotSendWhenUserDoesNotExist()` (anti-énumération)
- `shouldInvalidatePreviousUnconsumedTokensForUser()`
- `shouldRateLimitOnePerMinutePerPhone()`
- `shouldHashOtpNotStorePlaintext()`

### `ResetPasswordUseCaseTest`
- `shouldResetPasswordWhenCodeValid()`
- `shouldRejectExpiredCode()` (`INVALID_OR_EXPIRED_CODE`)
- `shouldRejectAlreadyConsumedCode()`
- `shouldLockTokenAfterFiveFailedAttempts()` (`CODE_LOCKED`)
- `shouldRevokeAllSessionsAcrossAllMemberships()`
- `shouldRejectWeakPassword()` (min ≥8/≥1 chiffre)
- `shouldEmitPasswordResetEvent()`

### Controller slice (`@WebMvcTest`)
- `shouldReturn200AlwaysOnForgotPassword()` (existence-c agnostic)
- `shouldReturn429WhenRateLimited()`
- `shouldReturn422OnInvalidCode()`

---

## 9. Décisions à valider (checklist PO)

- [ ] R1 : WhatsApp OTP seul en V1 (pas email/SMS) ?
- [ ] R2 : TTL 10 min + single-use OK ?
- [ ] R3 : rate-limits 1/min + 5/hour request, 5 attempts verify — confirmer les seuils.
- [ ] R4 : `forgot-password` toujours 200 (anti-énumération) — confirmer (vs révéler 404 pour UX). Recommandé : toujours 200.
- [ ] R5 : `passwordChangeRequired=false` après reset (l'utilisateur vient de le définir).
- [ ] R6 : révocation de session cross-membership (ties 12.2) — confirmer la dépendance 12.2 d'abord.
- [ ] R7 : fallback SUPER_ADMIN (V2) — in scope V1 ou non ?
- [ ] 4.3 : `sendOtp` dédié vs méthode `send` générique sur `WhatsAppPort` ?

---

## 10. Mapping des artefacts BMAD à mettre à jour

### 10.1 `requirements-inventory.md`
- **Nouvelle FR (proposition FR3b — à sloter après FR3)** :
  > **FR3b** : Un utilisateur (OWNER ou EMPLOYEE) qui a oublié son mot de passe peut le réinitialiser de façon autonome via un code à usage unique envoyé par WhatsApp (OTP 6 chiffres, TTL 10 min, single-use, rate-limité). Le reset révoque toutes les sessions actives de l'utilisateur.

### 10.2 Story 1.3 (`1-3-jwt-authentication-session-management`)
- **Nouvel AC — AC forgot/reset** : endpoints `POST /api/v1/auth/forgot-password` + `POST /api/v1/auth/reset-password` (publics, anti-énumération, rate-limit, révocation session). Cross-ref cette spec.

### 10.3 ARCH23 (WhatsApp port)
- Étendre `WhatsAppPort` avec `sendOtp` (ou `send` générique).

### 10.4 Backlog remédiation (`epics-remediation-audit.md`)
- Ajouter **Story 14.12** (piste C — V1-shippable + refonte ré-applique) pointant vers cette spec, dépendance Story 12.2.

---

## 11. Hors-scope (explicite)

- Email/SMS fallback (V2 si WhatsApp indisponible).
- OTP par téléphone (appel vocal) — V2.
- Reset par le SUPER_ADMIN (R7, V2).
- « Se souvenir de cet appareil » / bypass OTP sur appareil de confiance — V2.
- Biometic-only login bypassant le mot de passe — V2.
- Notification au OWNER quand un employé reset son propre mot de passe (pas nécessaire — c'est self-service légitime).
