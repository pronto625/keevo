---
title: 'P01 — Keevo theme (Keevo visual identity) selectable in Settings'
type: 'feature'
created: '2026-11-28'
status: 'done'
baseline_revision: '00daac21cd08b22d77bd53fc965e59758421a5c4'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/planning-artifacts/parite-keevo-v1-bref/SPEC.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Pre-existing Bref palette colours used as text fail WCAG AA (Bref light status/brand colours, Bref dark error).
    evidence: |-
      Computed from token values: Bref light successStrong #16A34A 3.3:1, warningStrong #D97706 3.2:1, primaryStrong #B38000 3.5:1, success #34C759 2.2:1, primary #FFBD18 1.7:1; Bref dark error #DC3545 3.9:1. Not caused by P01 (these are the Bref colours P01 must keep unchanged); routed to mp10-contraste-aa-bref.
    location: >-
      constants/theme.ts brefLight / brefDark
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Keevo V1 users moving to the React Native app (Bref Point) land on an unfamiliar yellow / warm-grey look; the app only offers a light/dark switch and many screens hard-code Bref colours, so no other palette can be applied cleanly.

**Approach:** Turn the colours into a token layer with two brands — "Bref" (current look, default) and "Keevo" (V1 "Indigo Sky" palette) — each in light and dark, selectable in Settings › Apparence together with an appearance mode Clair / Sombre / Système, persisted on the device and applied instantly everywhere; route hard-coded in-app colours to tokens.

## Boundaries & Constraints

**Always:**
- Code lives in the AI/bref worktree `/var/tmp/keevo-e2e/bref-parite`, branch `feat/parite-keevo-v1` (all paths below are relative to it). French commit messages. Never commit `.env*`.
- Keevo values come from V1 `keevo/app/lib/core/theme/app_theme.dart` (`git -C /home/toor/Project/FreeLance/AI/Keevo show origin2/release/2.0.x:keevo/app/lib/core/theme/app_theme.dart`): light primary `#3B5BDB` with white on it, gradient end `#4DABF7`, secondary/coral `#FF6B6B`, success `#51CF66` / strong `#2B7A3B`, warning `#FCC419` / strong `#A85500`, error `#FA5252` / strong `#C92A2A`, scaffold `#F8F9FA`, soft tint `#EEF1FE`, greys `#868E96 #ADB5BD #E9ECEF #F1F3F5`; dark primary `#4DABF7` with `#0D1B2A` on it, primary container `#1B3A5C`, surface `#0D1B2A`, surface variant `#1B2838`, on-surface `#F0F4F8`, on-surface-variant `#ADB5BD`, error `#FF6B6B`, success `#69DB7C`, warning `#FFD43B`, soft tint `#16223A`; containers success `#D3F9D8`/`#1B4332`, warning `#FFF9DB`/`#462F00`, error `#FFE3E3`/`#5C1A1A`.
- Body and label text on its background meets WCAG AA (≥ 4.5:1; ≥ 3:1 for icons and ≥ 18 pt text) in all four combinations; status colours used as text use the "strong" token, never the fill token.
- One source of truth per brand×mode: CSS variables (uniwind/HeroUI) and the JS token object (`useColors()`) carry the same palette; screens read tokens, never brand hex.
- French labels with fr + en keys; other locales fall back. No technical value (theme ids) shown on screen.
- The choice survives app restart and logout (only account data is cleared at logout).
- Existing Bref users keep the exact current look by default (brand `bref`, their stored light/dark mode).

**Never:**
- No per-screen `if keevo` overrides; no backend change (the API exposes no reliable "comes from Keevo V1" flag, so the default stays Bref).
- Do not restyle printed/shared documents: `features/invoice/components/invoice-templates/*` (PDF/receipt HTML) keep their colours.
- Keep fixed third-party brand colours (WhatsApp `#25D366`, Google `#4285F4`, WhatsApp chat imitation in `features/whatsapp-inbox`), shadows (`#000`), and white content on fixed coloured fills.
- No font family change (Outfit stays for both brands), no logo/app-name/splash/native icon change (needs a store rebuild; out of scope).
- Do not touch other branches of AI/bref, nor the backend.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh install | no stored brand, no stored mode | brand `bref`, mode `light` (current default) → uniwind theme `light` | No error expected |
| Legacy Bref user | stored mode `dark` (old key), no brand | brand `bref`, mode `dark` → theme `dark` | No error expected |
| Keevo light | brand `keevo`, mode `light` | theme `keevo-light`, `useColors()` returns Keevo light tokens | No error expected |
| Keevo + system | brand `keevo`, mode `system`, OS dark | theme `keevo-dark`; OS switches to light while app open → `keevo-light` live | OS scheme null → treated as light |
| Bref + system | brand `bref`, mode `system`, OS dark | theme `dark` | No error expected |
| Corrupt storage | stored brand `foo` / mode `x` | ignored → defaults (`bref`, `light`) | No crash; read failure → defaults |
| Logout / restart | brand `keevo`, mode `dark`, then logout or kill app | reopened app (login screens too) still Keevo dark | No error expected |

</intent-contract>

## Code Map

- `global.css` -- uniwind/HeroUI CSS variables; today `@variant light` / `@variant dark` (Bref). Add `@variant keevo-light` / `@variant keevo-dark` with the same variable set (accent, accent-foreground, accent-soft*, background, foreground, muted, surface*, overlay*, field-*, segment*, default*, border, secondary*, success, warning, danger). Used by HeroUI components and classes `text-muted`(124) `text-foreground`(107) `bg-surface`(57) `bg-surface-secondary` `text-accent` `bg-accent-soft` `bg-background`.
- `metro.config.js:18` -- `withUniwindConfig(config, { cssEntryFile })`: add `extraThemes: ['keevo-light', 'keevo-dark']` (uniwind 1.9.0 supports it, see `node_modules/uniwind/dist/metro/index.d.ts`). `uniwind-types.d.ts` is regenerated by uniwind (commit the regenerated file).
- `constants/theme.ts` -- `Palette` (Bref raw) and `Colors.light/dark` (Bref semantic). Add Keevo raw palette and a `Themes: Record<'bref'|'keevo', {light, dark}>` with one shared `ColorTokens` type; keep `Colors` = Bref for back-compat. Note: `secondary` is used app-wide as the strong neutral text/icon colour (e.g. settings labels), not as an accent — map Keevo `secondary` to a strong neutral (light ≈ `#2B3445`, dark ≈ `#DEE2E6`), and expose the coral as an extra token.
- `lib/theme/index.tsx` -- `ThemeContextProvider` (AsyncStorage key `@bref/color-scheme`, `toggleTheme`, `Uniwind.setTheme`). Replace by brand + appearance state, system listener (`Appearance.addChangeListener`), resolved scheme; keep `useTheme().colorScheme` returning the resolved `'light'|'dark'`.
- `hooks/use-colors.ts`, `hooks/use-color-scheme.ts`, `hooks/use-theme-color.ts`, `components/ui/collapsible.tsx:24` -- read `Colors[scheme]` directly; must read the active brand's tokens.
- `app/_layout.tsx:311` (`ThemeProvider value={DarkTheme|DefaultTheme}`) and `:386` (`<StatusBar style="auto" />`) -- navigation theme from tokens; status bar style from the resolved scheme (auto follows the OS, not the app).
- `app/(tabs)/_layout.tsx` -- tab bar uses tokens, but the central "+" button hard-codes `#FFBD18` fill/shadow and `#1A1917` icon → `primary` / `onPrimary`.
- `features/profile/screens/settings-screen.tsx:62-95` -- the light/dark `Switch` to replace; also `#FF3B30` danger title.
- `lib/i18n/locales/{fr,en}/profile.json` -- `profile.theme`, `profile.themes.{light,dark}` exist; add brand names, appearance mode labels (Clair / Sombre / Système), hints.
- `lib/clear-local-data.ts` -- logout clears only account keys (`store-stock:*`, sync keys); theme keys must not be added there.
- Hard-coded colours: 95 files contain hex/rgba/`Palette.` (count with `grep -rlE "#[0-9A-Fa-f]{3,8}\b|Palette\.|rgba\(" app features components lib hooks`). Heaviest in-app ones: `features/dashboard/screens/{report-detail,dashboard,reports}-screen.tsx` (dashboard `#32302C` hero card at :395, `#1A1917` badge text :240, `#fff1f2/#fecdd3` overdue card :449), `features/user/screens/{current-plan,plan-payment,plan}-screen.tsx`, `components/ui/{update-required-screen,app-update-overlay}.tsx` (`Palette.*`), `features/invoice/screens/invoice-detail-screen.tsx`, `features/invoice/components/add-invoice/*`, `features/insights/screens/profitability-screen.tsx`, `features/inventory/screens/*`, `features/bref-point/screens/*`, `features/find-invoice/screens/*`, `features/profile/screens/profile-screen.tsx`, `components/ui/{app-button,app-chip,app-date-picker,grouped-list,app-text-field,confirm-dialog,app-language-switcher}.tsx` (`#484641` secondary/outline/chip, `#7F7B75`, `#DC3545`), `features/pos/screens/pos-screen.tsx:481` (`#FFF1C7`/`#7A5200` low stock), `features/product/components/{stock,margin}-badge.tsx`, `features/user/utils/role-style.ts`, `components/route-error-boundary.tsx`, `components/sync/freshness-notice.tsx`, `features/authentication/screens/onboarding-screen.tsx:96` (`rgba(36,35,33,0.6)`).
- `app.json` -- `userInterfaceStyle: automatic` (system mode works); splash `#1A1917` stays (native, out of scope).

## Tasks & Acceptance

**Execution:**
- `lib/theme/theme-preferences.ts` (new, pure, no React Native import) -- types `ThemeBrand = 'bref'|'keevo'`, `Appearance = 'light'|'dark'|'system'`; `parseBrand`, `parseAppearance` (invalid → default), `resolveScheme(appearance, systemScheme)`, `uniwindThemeName(brand, scheme)` (`bref` → `light|dark`, `keevo` → `keevo-light|keevo-dark`); storage key constants (`@bref/theme-brand`, existing `@bref/color-scheme` now also accepts `system`) -- testable core of the matrix.
- `scripts/theme-preferences.test.mjs` (new) -- `node:test` cases for every matrix row reachable in pure code (defaults, legacy dark, keevo light/dark, system dark/light/null, corrupt values); run with `node --test scripts/theme-preferences.test.mjs` (Node 24 strips TS types on import of the `.ts` file).
- `constants/theme.ts` -- add `KeevoPalette`, `ColorTokens` type, `Themes.bref.{light,dark}` (current values unchanged) and `Themes.keevo.{light,dark}`; add the semantic tokens needed to replace hard-coded colours (at least: `primarySoft`/`onPrimarySoft`, `accent2` (coral), `successStrong`/`successSoft`/`onSuccessSoft`, `warningStrong`/`warningSoft`/`onWarningSoft`, `dangerStrong`/`dangerSoft`/`onDangerSoft`, `heroCard`/`onHeroCard`/`onHeroCardMuted`, `chipSelected`/`onChipSelected`) with Bref values equal to the colours they replace, so the Bref look is unchanged.
- `global.css` -- add `keevo-light` and `keevo-dark` variants (full variable set, values from the Keevo tokens; accent = primary, accent-foreground = on-primary).
- `metro.config.js`, `uniwind-types.d.ts` -- register the extra themes.
- `lib/theme/index.tsx` -- provider with `brand`, `appearance`, resolved `colorScheme`, `setBrand`, `setAppearance` (keep a `toggleTheme` only if still used); reads both keys at start, writes on change, applies `Uniwind.setTheme(uniwindThemeName(...))`, follows OS changes when `appearance === 'system'`; memoised context value.
- `hooks/use-colors.ts`, `hooks/use-theme-color.ts`, `components/ui/collapsible.tsx` -- return `Themes[brand][scheme]`.
- `app/_layout.tsx` -- navigation theme built from tokens (background, card, text, border, primary, notification); `StatusBar` style `light` on dark scheme, `dark` on light.
- `features/profile/screens/settings-screen.tsx` -- Apparence section: "Thème" choice Bref / Keevo (two selectable cards with a small colour swatch, selected state visible), "Affichage" choice Clair / Sombre / Système (segmented); instant apply; replace hard-coded `#FF3B30` by the danger token.
- `lib/i18n/locales/fr/profile.json`, `lib/i18n/locales/en/profile.json` -- new labels (e.g. fr "Thème", "Bref", "Keevo", "Couleurs Bref (jaune)", "Couleurs Keevo (bleu)", "Affichage", "Clair", "Sombre", "Système").
- In-app files listed in the Code Map (and every other file under `app/`, `features/` (except invoice templates and whatsapp-inbox chat imitation), `components/`) -- replace Bref brand colours (yellow family `#FFBD18 #FFD666 #FFC933 #D99E0A #B38000 #8C6300 #7A5200 #FFF1C7 #FFF6E0 #FFFBF0 #FEF9C3 #FEF3C7 #854D0E #92400E`, warm greys `#484641 #32302C #2F2F2F #1A1917 #242321 #F0EDE6 #F5F3F0 #7F7B75 #5A5752 #ECEDEE`, `Palette.*`) and neutral text/surface hex that ignore dark mode (`#111 #111827 #1A1A1A #1A1A2E #222 #333 #374151 #444 #4B5563 #666 #6B7280 #999 #9CA3AF #E5E7EB #DDD #D1D5DB` and white surfaces) by tokens; status hex (`#16A34A #22C55E #DC2626 #EF4444 #FF3B30 #DC3545 #F59E0B #EA580C`, their soft backgrounds `#DCFCE7 #FEE2E2 #fff1f2 #fecdd3 #FDECEA`…) by status tokens. Content on a primary fill uses `onPrimary` (Bref dark text on yellow, Keevo white on indigo). After the pass, `grep -rniE "FFBD18|484641|32302C|1A1917|B38000|7A5200|FFF1C7|Palette\." app features components --include=*.tsx --include=*.ts | grep -v invoice-templates` returns nothing.

**Acceptance Criteria:**
- Given the app on the emulator with brand Bref, when the owner opens Profil › Paramètres › Apparence, picks "Keevo" then "Clair", then "Sombre", then "Système", then every main screen (accueil, ventes, caisse, produits, fiche produit, formulaire produit, modules, paramètres, création de facture, notifications) shows the Keevo palette (indigo `#3B5BDB` actions / sky `#4DABF7` in dark, navy `#0D1B2A` dark background) with no yellow or warm-grey Bref colour and no unreadable text, including tab bar, headers, buttons, chips, cards, inputs, badges, sheets, dialogs and status bar.
- Given Keevo dark is chosen, when the app is killed and reopened, and when the owner logs out, then the app (including the login screens) still shows Keevo dark.
- Given Keevo is chosen, when the owner selects "Bref", then every screen looks as before this story (same yellow / warm-grey colours in light and dark).
- Given any brand and mode, when screens are captured, then screenshots of each main screen exist for Bref light, Bref dark, Keevo light and Keevo dark under `_bmad-output/test-artifacts/parite-v1-bref-2026-11-28/videos/P01-screens/`, and the scenarios are filmed under `videos/P01-*.mp4` with `videos/P01.md` as index.

## Spec Change Log

### 2026-11-28 — Review pass (accepted deviation, no code re-derivation)
- Trigger: review finding "Bref tokens do not equal the colours they replace" and coordinator decision of 2026-11-28 (Bref look must not move; keep only genuine readability fixes).
- Amended: nothing inside `<intent-contract>`. Accepted deviation from "Existing Bref users keep the exact current look": 8 Bref-dark spots that were unreadable before keep their fix (Bref light unchanged everywhere):
  1. `components/ui/app-button.tsx` outline variant border/label #484641 → #7F7B75 on #1A1917 (1.86 → 4.18:1);
  2. `features/user/screens/current-plan-screen.tsx` outline-button icons, same change (1.67 → 3.73:1 on #242321);
  3. `features/dashboard/screens/reports-screen.tsx` highlighted card and period toggle #A3A099 → #484641 (white 2.61 → 9.43:1);
  4. `features/user/screens/plan-screen.tsx` period toggle, same (2.61 → 9.43:1);
  5. `features/user/screens/plan-screen.tsx` "popular" badge #D5D3CC on #A3A099 → on #484641 (1.74 → 6.29:1);
  6. `features/whatsapp-marketing/components/marketing-ui.tsx` selected choice chip (2.61 → 9.43:1);
  7. `features/dashboard/screens/report-detail-screen.tsx` hero card #A3A099 → #484641 (2.61 → 9.43:1);
  8. `features/shop/screens/shop-detail-screen.tsx` revenue card, same (white 2.61 → 9.43:1, yellow label 1.56 → 5.63:1).
- Known-bad state avoided: Bref pixels drifting through shared tokens (#EF4444→#DC2626, #1A1917/white on yellow → #484641, chips, report cards…).
- KEEP: Bref token value at a site = the literal it replaced (same in light and dark when the old code ignored the mode); Keevo values = what the site already showed in Keevo.

## Review Triage Log

### 2026-11-28 — Review pass
- verdicts: 42 findings — high 4, medium 15, low 15, false 8, maybe-false 0
- findings:
  - `[medium]` `[patch]` Blind: startup frame of Bref light before stored preferences load (no hydration gate) — provider renders nothing until the read settles, splash stays up.
  - `[low]` `[patch]` Blind: a choice made before getMany resolves is overwritten by the stored value — per-value "set by user" ref skips hydration of that value.
  - `[false]` `[reject]` Blind: ar/ha/ln/sw/wo locales miss the new keys — the intent says "fr + en keys; other locales fall back"; English fallback is the specified behaviour.
  - `[low]` `[patch]` Blind: global.css keevo-light comment claims equality with Themes.keevo while `--warning` (#FCC419) ≠ JS `warning` (#A85500) — they are deliberately different roles (CSS fill + foreground vs JS text "strong"); comment corrected to say so.
  - `[low]` `[reject]` Blind: Keevo dark `surface` = `background` (#0D1B2A), cards separated only by the border — V1 uses the same surface #0D1B2A (spec value); screenshots show cards separated by the #2A3B4F border, readable; changing it departs from V1.
  - `[medium]` `[patch]` Blind: native controls (date picker dialog, keyboard) ignore the app mode — same root cause as the Bref+Système defect: provider now calls `Appearance.setColorScheme(colorScheme)` (null in Système) after each theme change, so native UI follows the app mode.
  - `[low]` `[patch]` Blind: leftover hard-coded colours — plan-screen savings badge `#34C759/#FFFFFF` routed to success tokens (Bref values unchanged); modal backdrops `rgba(0,0,0,…)`, switch thumbs/`#fff` on fixed fills, `#8B5CF6`/`#E8892B` category tints are allowed by the intent (black backdrops and white-on-fixed-fill read correctly in every theme; tints are per-category, not brand) — rejected for those.
  - `[low]` `[reject]` Blind: hex+opacity string concatenation has no guard — all 12 tokens concatenated today are 6-digit hex (checked by the verification layer); a guard needs a helper or a module split, more than a direct correction, and developers rarely meet it.
  - `[medium]` `[patch]` Blind: the test is wired to no script — `"test": "node --test scripts/*.test.mjs"` added to package.json.
  - `[low]` `[patch]` Blind: unused Bref-only exports `Gradients` and `Colors` alias invite bypassing useColors() — both removed (no users).
  - `[false]` `[reject]` Blind: native splash / Android navigation bar do not follow the brand — the intent excludes splash/native changes ("no logo/app-name/splash/native icon change"); the nav bar is edge-to-edge transparent over app content.
  - `[false]` `[reject]` Blind: Bref dark inverse colours (#484641 on #1A1917) barely contrast — this is the pre-existing Bref dark look the intent requires to keep; tracked with the other pre-existing Bref contrast items in mp10-contraste-aa-bref.
  - `[low]` `[patch]` Blind: `useThemeColor` overrides keyed by mode only — no caller passes lightColor/darkColor (grep empty); hook simplified to read useTheme() once.
  - `[high]` `[patch]` Edge: Bref + Système never follows the phone — verified in uniwind 1.9.0 `setTheme`: built-in light/dark call `Appearance.setColorScheme(theme)`, so the listener reports the forced scheme; provider now resets `Appearance.setColorScheme(null)` in Système and forces the scheme otherwise.
  - `[high]` `[patch]` Edge: initial system scheme read after the module-level `setTheme('light')` forced light — OS scheme captured before any setTheme (same fix group as above).
  - `[medium]` `[patch]` Edge: Keevo themes set Appearance to "unspecified", native dialogs/pickers/keyboard stay in phone mode — same fix group as native controls.
  - `[low]` `[patch]` Edge: choice before hydration overwritten — same group as the Blind finding (ref guard).
  - `[medium]` `[patch]` Edge: Bref flash at cold start for Keevo users — same group as the Blind finding (hydration gate under the splash).
  - `[low]` `[patch]` Edge: `useThemeColor` `??` keeps an empty-string prop where the old `||` fell back — restored `||`.
  - `[false]` `[reject]` Edge: ar/ha/ln/sw/wo missing keys — specified English fallback (see Blind row).
  - `[false]` `[reject]` Edge: first-install persist of `@bref/color-scheme` removed — `parseAppearance` defaults to light, no functional consequence (layer itself reports none).
  - `[high]` `[patch]` Edge: claim "follows OS changes when Système" false for Bref — same group as Bref + Système.
  - `[medium]` `[patch]` Edge: claim "Bref tokens equal the colours they replace" false (#DC2626→#DC3545, #EA580C→#D97706, #FFF3F0/#FF3B30, #FFF8E6/#B38000, #1A1A1A, #E8E8E8/#888/#AAA, primary50, primary400/600, neutral100…) — every removed Bref literal restored at its site (new tokens where needed); only genuinely unreadable Bref-dark spots keep their fix, logged in the Spec Change Log.
  - `[medium]` `[patch]` Edge: claim "Keevo dark still shown after restart" only met after a Bref-light frame — same group as the hydration gate.
  - `[medium]` `[patch]` Verification: theme test runs only by hand — package.json `test` script added.
  - `[medium]` `[patch]` Verification: test checks a copy of the provider logic (`themeFor`), not the provider — provider decisions moved to pure exported functions (hydration, native scheme) used by the provider and tested directly; the React wiring itself stays a device check (no component test setup in the repo).
  - `[low]` `[reject]` Verification: nothing checks CSS/JS palette parity or 6-digit hex — values verified equal today; constants/theme.ts imports react-native so a Node guard needs a module split, more than a direct correction.
  - `[high]` `[patch]` Verification (other): Bref + Système cannot follow the phone — same group as the Edge finding.
  - `[medium]` `[patch]` Intent: diff implements the loose Bref-preservation reading (Bref pixels move: #FF3B30→#DC2626, #FFF3F0→#FEE2E2, #E8E8E8→#D4D1CC, #888888→#5A5752, margin badge, white surfaces in Bref dark) — same group as the Edge "Bref tokens" claim; restored.
  - `[low]` `[reject]` Intent: Keevo light general `success/warning/error` tokens take the strong values, V1 fills unused in JS — these tokens are used app-wide as text, and the intent requires strong for text; fills remain in CSS (HeroUI) and soft containers; readable everywhere.
  - `[false]` `[reject]` Intent: invented Keevo values (#2B3445 secondary, #5C636A textSecondary) — #2B3445 is the spec's stated mapping; V1 grey #868E96 is 3.3:1 on #F8F9FA, so a darker textSecondary is required by the AA constraint.
  - `[false]` `[reject]` Intent: `#8B5CF6` (bref-point-intro) and `#E8892B` (grouped-list) remain — per-category illustration/icon tints, not brand colours: they read the same in every theme and are not Bref colours, so no leftover brand colour.
  - `[medium]` `[patch]` Intent: provider untested, Bref-light frame at launch — same groups as hydration gate and pure-function tests.
  - `[low]` `[patch]` Intent: CSS/JS drift on `--warning` in keevo-light — same group as the Blind comment fix.
  - `[medium]` `[defer]` Intent: Bref light status/brand colours used as text fail AA (3.3, 3.2, 3.5, 2.2, 1.7:1) — pre-existing Bref colours that P01 must keep unchanged; routed to mp10-contraste-aa-bref.
  - `[medium]` `[defer]` Intent: Bref dark error #DC3545 3.9:1 on background — pre-existing Bref value; routed to mp10-contraste-aa-bref.
  - `[false]` `[reject]` Intent: Keevo light textDisabled about 2.0:1 — WCAG 1.4.3 exempts disabled (inactive) components.
  - `[medium]` `[patch]` Intent: Bref regression surface not checked — every removed Bref literal statically resolved old vs new per site (only the 8 logged Bref-dark readability fixes differ); Bref light/dark screenshots of the 10 main screens retaken on 3f95027 and inspected.
  - `[low]` `[patch]` NFR11: `paymentStatusColors(colors)` / `planUiConfig(colors)` rebuilt per row inside `.map` — computed once per render with useMemo.
  - `[low]` `[patch]` NFR11: duplicated helpers `paymentStatusColors`, `planUiConfig` — moved to one shared util each.
  - `[low]` `[patch]` NFR11: `useThemeColor` reads the context through two hooks — reads useTheme() once.
  - `[medium]` `[patch]` NFR11: startup frame of Bref light — same group as the hydration gate.
- coordinator items (2026-11-28) handled in the same patch round: Keevo dark white text on error `#FF6B6B` (2.8:1) → on-error navy; "business" plan card light grey in Keevo dark → dedicated dark card token.

## Design Notes

Pattern: **design tokens + provider (strategy by brand×mode)**. A pure resolver maps (brand, appearance, OS scheme) to one theme id; the provider is the single writer (AsyncStorage + `Uniwind.setTheme`); screens are read-only consumers through `useColors()` (JS tokens) or uniwind classes (CSS variables). Adding a brand later = one palette object + one CSS variant, no screen change. Cost: `useColors()` is an O(1) lookup in a static map; the context value is memoised so a theme change re-renders consumers once; no network, no storage read per render (2 AsyncStorage reads at start, 1 write per change). Keevo `secondary` is a strong neutral because the codebase uses `secondary` for primary text and icons; the V1 coral is exposed as `accent2` and only used where V1 used it (prices/accents) if at all. Bref tokens must equal the hex they replace, so the Bref look does not move.

## Verification

**Commands:**
- `cd /var/tmp/keevo-e2e/bref-parite && pnpm exec tsc --noEmit` -- expected: no error
- `cd /var/tmp/keevo-e2e/bref-parite && pnpm lint` -- expected: no new error versus baseline
- `cd /var/tmp/keevo-e2e/bref-parite && node --test scripts/theme-preferences.test.mjs` -- expected: all pass
- `cd /var/tmp/keevo-e2e/bref-parite && grep -rniE "FFBD18|484641|32302C|1A1917|B38000|7A5200|FFF1C7|Palette\." app features components --include=*.tsx --include=*.ts | grep -v invoice-templates` -- expected: empty

**Manual checks (if no CLI):**
- Release APK on `emulator-5554`, scenarios filmed and screenshots taken per SPEC.md "Verification standard"; screenshots inspected for leftover Bref colour and contrast in Keevo light/dark.

## Auto Run Result

**Summary.** Two brands (Bref, default; Keevo, V1 "Indigo Sky") × appearance Clair / Sombre / Système, chosen in Paramètres › Apparence, persisted on the device (keys `@bref/theme-brand`, `@bref/color-scheme`, untouched by logout), applied instantly through one token layer (`constants/theme.ts` Themes + `global.css` uniwind variants `keevo-light`/`keevo-dark`). Hard-coded in-app colours routed to tokens. App commits on `feat/parite-keevo-v1`: `134a536` (implementation), `3f95027` (review fixes), worktree `/var/tmp/keevo-e2e/bref-parite`.

**Files changed (main ones).**
- `lib/theme/theme-preferences.ts` — pure core: parse, resolve, uniwind theme name, `hydratePreferences`, `nativeColorScheme`, storage keys.
- `lib/theme/index.tsx` — provider: single writer, hydration gate under the splash, OS scheme captured before any setTheme, `Appearance.setColorScheme` follows the app mode (null in Système).
- `scripts/theme-preferences.test.mjs`, `package.json` (`pnpm test`) — 10 node:test cases.
- `constants/theme.ts` — `ColorTokens`, `Themes.bref/keevo.light/dark`, site tokens whose Bref value equals the replaced literal; `Gradients` and `Colors` alias removed.
- `global.css`, `metro.config.js`, `uniwind-types.d.ts` — Keevo CSS variants registered.
- `hooks/use-colors.ts`, `hooks/use-theme-color.ts`, `components/ui/collapsible.tsx`, `app/_layout.tsx` — read the active brand; navigation theme and status bar from tokens.
- `features/profile/screens/settings-screen.tsx`, `lib/i18n/locales/{fr,en}/profile.json` — Apparence section (brand cards, Clair/Sombre/Système).
- `features/invoice/utils/payment-status-colors.ts`, `features/user/utils/plan-colors.ts` — shared colour tables, memoised per render.
- ~60 screens/components under `app/`, `components/`, `features/` — hard-coded colours → tokens.

**Review findings.** 42 findings (high 4, medium 15, low 15, false 8). Patches applied: 4 high (Bref + Système not following the phone ×3 rows, one root cause), 13 medium and 11 low patch rows (see Review Triage Log). Deferred: 2 medium rows (pre-existing Bref palette AA contrast) → `mp10-contraste-aa-bref` (key + brief in stories.yaml). Rejected: 8 false (specified English fallback for other locales ×2, removed first-install persist, native splash/nav bar out of intent, pre-existing Bref dark inverse look, V1-justified Keevo values, disabled-text contrast exemption, category tints) and 4 low (Keevo dark surface = V1 value, hex+alpha guard, CSS/JS parity guard needing a module split, Keevo light status tokens = strong by the text rule) with reasons in the triage log. Coordinator items: Keevo dark on-error navy (6.27:1), Business plan card dark (10.5:1), startup Bref frame removed.

**Follow-up review recommendation: true.** First pass patched 4 high-verdict rows and more than two medium entries. Specific unverified risk: the reworked provider (`Appearance.setColorScheme` after every theme change + hydration gate) was verified on the Android emulator only; iOS (date picker spinner, keyboard appearance) and a slow-storage cold start were not exercised.

**Verification.**
- `pnpm exec tsc --noEmit`: 43 errors, all in pre-existing untouched files (in-memory mocks, invoice-details-modal, create/update hooks, user.model); none in changed files.
- `pnpm lint`: 6 problems (1 error, 5 warnings), identical to the baseline.
- `pnpm test` / `node --test scripts/theme-preferences.test.mjs`: 10/10 pass.
- Spec grep for Bref literals: empty.
- Release APK (x86_64) of `3f95027` on `emulator-5554`, local backend: clip `P01-04-revue-systeme-bref-demarrage-keevo.mp4` (Bref + Système follows the phone live, Keevo dark Business card and red badge, cold start with no Bref frame, back to Bref Clair); 40 screenshots of the 10 main screens in the four themes retaken and inspected (Bref as before, Keevo without Bref colour, text readable). Earlier clips P01-01..03 from `134a536` remain valid for switching, restart and logout.

**Residual risks.** Pre-existing Bref AA contrast (→ mp10). 75 new site tokens make `constants/theme.ts` large (the price of keeping Bref pixel-identical). iOS not tested. Native splash stays `#1A1917` (out of scope).
