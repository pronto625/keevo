---
title: Parité Keevo V1 (prod) → app React Native Bref Point
status: ready
created: 2026-11-28
owner_request: "toutes, je dis bien toutes les vues et fonctionnalités de Keevo doivent être reportées et testables dans Bref" (user, 2026-11-28)
companions:
  - _bmad-output/test-artifacts/parite-v1-bref-2026-11-28/RAPPORT.md
  - _bmad-output/test-artifacts/parite-v1-bref-2026-11-28/lot-A-compte.md
  - _bmad-output/test-artifacts/parite-v1-bref-2026-11-28/lot-B-caisse.md
  - _bmad-output/test-artifacts/parite-v1-bref-2026-11-28/lot-C-stock.md
  - _bmad-output/test-artifacts/parite-v1-bref-2026-11-28/lot-D-pilotage.md
  - _bmad-output/test-artifacts/e2e-rn-2026-11-23/RAPPORT.md
---

# Epic P — Keevo V1 parity in the Bref Point React Native app

## Goal

Every screen and user-visible function of Keevo V1 2.0.1 (the Flutter app in production, branch `release/2.0.x`,
`keevo/app/lib`) exists in the React Native app (Bref Point), behaves at least as well, and is verified on a device.
A Keevo V1 user (owner **or** employee) who installs the new app on go-live day must find every function they used,
with their existing data (accounts, sales history, products, archived items, drafts, notifications).
Plus a **Keevo theme** (Keevo visual identity) selectable in Settings so V1 users are not lost.

The gap list is the 2026-11-28 audit (companions). Each item there marked ABSENT / PARTIEL / CASSÉ / DIFFÉRENT
(not acceptable) for a V1 function is in scope of exactly one story below. Bref-only functions already in the app
must keep working (no regression).

## Where the code lives (read carefully)

- **App (main deliverable)**: repo `AI/bref`, worktree **`/var/tmp/keevo-e2e/bref-parite`**, branch
  **`feat/parite-keevo-v1`** (based on `00daac2` = `fix/e2e-corrections-2026-11-23`, which already contains the
  mobile developer's `br-migrate-api-to-keevo`). Commit there with clear French messages; push **only this branch**
  to `origin` (`git -C /var/tmp/keevo-e2e/bref-parite push origin feat/parite-keevo-v1`). Never commit to, rebase or
  push the other developer's branches (`develop`, `main`, `br-migrate-api-to-keevo`). Never commit `.env*` or
  secrets. Stack: Expo SDK 54, RN 0.81, Expo Router (`app/`), features in `features/*`, SDK in `lib/sdk`,
  WatermelonDB in `lib/database`, i18n in `lib/i18n/locales/<lang>/*.json` (fr is the reference, add en too; other
  languages fall back), styling uniwind/Tailwind (`global.css`, `lib/theme`, `constants/theme.ts`).
  Checks: `pnpm exec tsc --noEmit`, `pnpm lint`, existing tests if present for the touched area.
- **Backend (only if the API truly lacks something)**: `AI/Keevo/keevo/backend` on `refonte/modulaire`. Additive,
  V1-2.0.1-compatible changes only, with tests run package by package (never the whole suite at once). Push each
  commit to `origin` and `origin2`, branch and `develop` (fast-forward). Never deploy.
- **Specs / tracking**: this folder (`stories/<id>-<slug>.md`) in the Keevo repo, committed and pushed like the
  backend. Each story also gets a key `mp<nn>-<slug>` in the MOBILE block of
  `_bmad-output/implementation-artifacts/sprint-status.yaml`.
- **V1 reference**: read the V1 code with `git -C /home/toor/Project/FreeLance/AI/Keevo show origin2/release/2.0.x:keevo/app/lib/<path>`
  (an extracted copy may exist under the session scratchpad; do not rely on it).

## Product rules (non-negotiable)

1. French UI labels, no technical data on screen (no UUIDs, raw codes, enum names, JSON, `XAF` → `FCFA`).
2. Offline-first like V1: a write the user can do offline in V1 must be accepted offline (queued, durable, sent
   once) or clearly refused with a French message; never a spinner that never ends.
3. Roles: V1 owner → `owner`, V1 manager → `admin`, V1 employee → `seller`. A seller must keep every V1 employee
   function (POS, own store stock in read, inventory counting, transfer reception, day closure, today's report…)
   unless a custom role (19-14) removes it. Owner-only V1 functions stay owner (+ admin where the API allows).
4. Data of an existing V1 company: the backend migration V50 disables `invoicing` for every existing tenant, so
   anything a V1 user relied on must work **without** the invoicing module (sales come from `/sales/history`, not
   from automatic invoices). V41 leaves V1 employees with a phone + password and **no username**.
5. Design pattern and complexity rule FUS-NFR11 applies to app code too (no N+1 request loops per row, paginate,
   cache with correct invalidation, no full-list refetch on each screen focus).
6. Never open a real share sheet / WhatsApp / wa.me on the Redmi (personal WhatsApp). Use the emulator for anything
   that shares.

## Verification standard (every story)

A story is `done` only when, in addition to the build-auto review:
1. A release APK is built from the worktree and installed on the emulator `emulator-5554` (AVD `keevo_e2e`,
   `ANDROID_AVD_HOME=/var/tmp/keevo-e2e/avd`). Build: `cd /var/tmp/keevo-e2e/bref-parite && npx expo prebuild
   --platform android` (only if `android/` is missing or native config changed) then `cd android && ./gradlew
   assembleRelease`, `adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk`.
   `.env` must contain `EXPO_PUBLIC_API_BASE_URL=http://localhost:4500/api/v1`.
2. The local backend runs on port 4500 against database `keevo_e2e` (docker container `keevo_postgres`), started
   with messaging off: `KEEVO_WHATSAPP_PROVIDER=noop KEEVO_FCM_ENABLED=false KEEVO_EXPO_PUSH_ENABLED=false
   KEEVO_SMS_PROVIDERS= KEEVO_EMAIL_PROVIDERS= KEEVO_PAYMENTS_GATEWAY=disabled POSTGRES_DB=keevo_e2e
   KEEVO_OTP_REVIEW_IDENTIFIER=+237690000001 KEEVO_OTP_REVIEW_CODE=246810 mvn -q spring-boot:run -DskipTests`.
   Stop it only with `fuser -k 4500/tcp`. Emulator network: `adb reverse` + relay and HTTP proxy — use the tools in
   `_bmad-output/test-artifacts/e2e-rn-2026-11-23/tools/` (`k.py emu on`, `proxy.py 8888` +
   `settings put global http_proxy 10.0.2.2:8888`, `kdrive.py`, `takes.py` for videos).
3. Test accounts in `keevo_e2e`, tenant `kv_6s0qob`: owner `+237690000001` (OTP review code `246810`); a **V1-shaped
   employee** Awa Ngo: phone `+237690000050`, password `Vendeur2024`, no username (keep it that way as the V1
   fixture); `invoicing` is owner-disabled for this tenant (V1 fixture, keep it). Seller `paul.vendeur` exists.
4. Every acceptance scenario of the story is played on the emulator and **recorded** (`adb shell screenrecord`, max
   3 min per clip, pulled to `_bmad-output/test-artifacts/parite-v1-bref-2026-11-28/videos/<story-id>-<nn>-<slug>.mp4`)
   with a short `videos/<story-id>.md` index (scenario → file → result OK/KO). Offline scenarios cut the network with
   the relay (`k.py emu off` / `on`), never airplane mode on the Redmi.
5. Server-side effects are checked in the database (`docker exec keevo_postgres psql -U keevo -d keevo_e2e`).

## Stories

See `stories.yaml`. Order: P01 theme, P02 accounts & login, P03 POS sales without invoicing, P04 modules
activation, P05 notifications, P06 catalogue & stock, P07 reporting & audit, P08 customers/suppliers/admin misc,
P09 durable offline & sync screens.
