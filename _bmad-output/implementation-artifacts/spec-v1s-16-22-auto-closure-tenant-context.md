---
title: 'v1s-16-22 Clôture automatique : chaque boutique dans le schéma de son entreprise'
type: 'bugfix'
created: '2026-10-19'
status: 'done'
baseline_revision: '95029f36'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-v1s-16-21-prod-1-1-70-fixes.md'
deferred: []
---

<intent-contract>

## Intent

**Problème :** constat en production le 2026-10-19 sur l'entreprise « Boutique 6e », boutique « Boutique A17 ». Le menu Rapports (Jour, puis clôture) affiche 116 ventes / 1 504 000 XAF. Le tableau de bord affiche 74 ventes / 1 300 500 XAF, et c'est lui qui a raison. L'écart correspond aux 42 ventes / 203 500 XAF du 11/10, jamais clôturées.

Enquête en lecture seule (logs + `SELECT` en transaction READ ONLY) :
- Le rapport compte les ventes depuis la dernière clôture. Pour A17, la dernière clôture date du 10/10.
- Chaque soir, `DayClosureAutoScheduler` clôture toutes les boutiques d'une entreprise en boucle. Après chaque clôture, `EndOfDayReportListener` (AFTER_COMMIT, synchrone, même thread) appelait `TenantContext.clear()` dans son `finally`.
- À partir de la 2e boutique, la clôture était donc écrite dans `public.day_closures` : 883 lignes du 2026-04-10 au 2026-10-18. Une seule boutique par passage horaire était réellement clôturée, donc 4 boutiques au plus par jour.
- A17 et Caisse B60 (5e et 6e boutiques) n'étaient jamais clôturées automatiquement. Les entreprises à plusieurs boutiques sont toutes touchées : kv_11b5zm, kv_1h82ba, kv_how9ho, kv_mjif35, kv_nr6dpx.

**Approche :** corriger la cause et ajouter une défense en profondeur, côté backend uniquement :
- `EndOfDayReportListener` restaure le tenant de l'appelant au lieu de l'effacer, comme `DayClosureWhatsAppListener`.
- `DayClosureAutoScheduler` repositionne le tenant avant chaque boutique.

## Boundaries & Constraints

- Aucune modification ni suppression de données en production. Les lignes égarées dans `public.day_closures` ne sont lues par rien. Leur purge est une opération séparée, à faire avec sauvegarde et accord explicite.
- Les clôtures déjà passées ne sont pas recréées. La prochaine clôture couvre, comme prévu (FR51 D1), toutes les ventes depuis la précédente.
- Tests par lots uniquement.

## Acceptance Criteria

- **AC1** : une entreprise à N boutiques est entièrement clôturée en un seul passage, dans son schéma, même si un listener efface le contexte.
- **AC2** : après le listener de rapport, le tenant de l'appelant est intact. Sans appelant, le contexte reste vide.

</intent-contract>

## Dev Agent Record

- 2026-10-19 : correctif dans `EndOfDayReportListener` (restauration du tenant précédent) et dans `DayClosureAutoScheduler` (tenant repositionné avant chaque boutique). Tests ajoutés : `scheduler_closesEveryStoreInTenantSchema_evenWhenAListenerClearsTheContext`, `onDayClosed_restoresTheCallersTenant` et `onDayClosed_withoutCallerTenant_leavesTheContextClear`.
