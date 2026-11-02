-- K1 — Keevo (schéma public uniquement) : tenants, utilisateurs, memberships, schémas kv_%, taille de base.
-- Lecture seule : uniquement des SELECT. Agrégats uniquement. Les tables de tenant (kv_xxxxxx.*) ne sont pas lues.
-- Tenants DELETED (16-6) : le schéma est supprimé, la ligne anonymisée reste ; ils sont exclus de la capacité (K1.b) et du
--   contrôle « tenants sans schéma » (K1.h) et comptés sur leur propre ligne.
-- Schéma : user_daily_activity, tenant_payments, tenant_plan_events, account_deletion_log existent dans la refonte (V2+) ;
--   sur une base plus ancienne (V1) les sections K1.k / K1.l sont marquées « non mesuré » plutôt que d échouer.
SELECT to_regclass('public.user_daily_activity') IS NOT NULL AS has_activity,
       to_regclass('public.tenant_payments') IS NOT NULL AS has_payments,
       to_regclass('public.tenant_plan_events') IS NOT NULL AS has_plan_events,
       to_regclass('public.account_deletion_log') IS NOT NULL AS has_deletion_log \gset

\echo
\echo '#### K1.a — Tenants par plan_type × status'
SELECT COALESCE(plan_type, 'TOTAL') AS plan_type, COALESCE(status, 'tous statuts') AS status, COUNT(*) AS tenants,
       COUNT(*) FILTER (WHERE deletion_scheduled_at IS NOT NULL) AS avec_suppression_programmee
FROM public.tenants
GROUP BY ROLLUP (plan_type, status)
ORDER BY plan_type NULLS LAST, status NULLS LAST;

\echo
\echo '#### K1.b — Capacité FUS-NFR3 : tenants non supprimés × 2 (DELETED comptés à part)'
SELECT COUNT(*) FILTER (WHERE status <> 'DELETED') AS tenants_non_supprimes,
       COUNT(*) FILTER (WHERE status = 'ACTIVE') AS tenants_actifs,
       COUNT(*) FILTER (WHERE status <> 'DELETED') * 2 AS capacite_cible_x2,
       COUNT(*) FILTER (WHERE deletion_scheduled_at IS NOT NULL AND status <> 'DELETED') AS suppression_programmee,
       COUNT(*) FILTER (WHERE status = 'DELETED') AS tenants_deleted,
       COUNT(*) AS lignes_tenants
FROM public.tenants;

\echo
\echo '#### K1.c — Tenants par année de création'
SELECT EXTRACT(YEAR FROM created_at)::int AS annee, COUNT(*) AS tenants FROM public.tenants GROUP BY 1 ORDER BY 1;

\echo
\echo '#### K1.d — Utilisateurs (public.users) par role × is_active'
SELECT role, is_active, COUNT(*) AS utilisateurs,
       COUNT(*) FILTER (WHERE locked_until IS NOT NULL AND locked_until > now()) AS verrouilles
FROM public.users GROUP BY 1, 2 ORDER BY 1, 2;

\echo
\echo '#### K1.e — Memberships (user_tenant_memberships) par role × is_active'
SELECT role, is_active, COUNT(*) AS memberships, COUNT(DISTINCT user_id) AS utilisateurs, COUNT(DISTINCT tenant_id) AS tenants
FROM public.user_tenant_memberships GROUP BY 1, 2 ORDER BY 1, 2;

\echo
\echo '#### K1.f — Utilisateurs par nombre de memberships (multi-tenant)'
WITH per_user AS (
  SELECT u.id, COUNT(m.id) AS n
  FROM public.users u LEFT JOIN public.user_tenant_memberships m ON m.user_id = u.id
  GROUP BY u.id)
SELECT n AS memberships_par_utilisateur, COUNT(*) AS utilisateurs FROM per_user GROUP BY 1 ORDER BY 1;

\echo
\echo '#### K1.g — Utilisateurs avec > 1 membership ; tenants sans aucun membership ; tenants sans OWNER actif'
SELECT
  (SELECT COUNT(*) FROM (SELECT user_id FROM public.user_tenant_memberships GROUP BY 1 HAVING COUNT(*) > 1) t) AS users_multi_membership,
  (SELECT COUNT(*) FROM public.tenants t WHERE NOT EXISTS (SELECT 1 FROM public.user_tenant_memberships m WHERE m.tenant_id = t.id)) AS tenants_sans_membership,
  (SELECT COUNT(*) FROM public.tenants t WHERE NOT EXISTS (
      SELECT 1 FROM public.user_tenant_memberships m WHERE m.tenant_id = t.id AND m.role = 'OWNER' AND m.is_active)) AS tenants_sans_owner_actif;

\echo
\echo '#### K1.h — Schémas kv_% (pg_namespace) vs tenants déclarés (hors DELETED)'
SELECT
  (SELECT COUNT(*) FROM pg_namespace WHERE nspname LIKE 'kv\_%') AS schemas_kv,
  (SELECT COUNT(*) FROM public.tenants WHERE status <> 'DELETED') AS tenants_non_supprimes,
  (SELECT COUNT(*) FROM public.tenants t WHERE t.status <> 'DELETED'
     AND NOT EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspname = t.schema_name)) AS tenants_sans_schema,
  (SELECT COUNT(*) FROM public.tenants t WHERE t.status = 'DELETED'
     AND EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspname = t.schema_name)) AS tenants_deleted_schema_restant,
  (SELECT COUNT(*) FROM pg_namespace n WHERE n.nspname LIKE 'kv\_%'
     AND NOT EXISTS (SELECT 1 FROM public.tenants t WHERE t.schema_name = n.nspname)) AS schemas_orphelins;

\echo
\echo '#### K1.i — Taille de la base, des schémas kv_% et de public (pg_total_relation_size des tables, index et TOAST inclus)'
SELECT pg_size_pretty(pg_database_size(current_database())) AS taille_base,
       pg_size_pretty(COALESCE(SUM(pg_total_relation_size(c.oid)) FILTER (WHERE n.nspname LIKE 'kv\_%'), 0)) AS taille_schemas_kv,
       pg_size_pretty(COALESCE(SUM(pg_total_relation_size(c.oid)) FILTER (WHERE n.nspname = 'public'), 0)) AS taille_public
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'm', 'p') AND (n.nspname LIKE 'kv\_%' OR n.nspname = 'public');

\echo
\echo '#### K1.j — Refresh tokens Keevo (public.refresh_tokens) : actifs / révoqués / expirés (proxy d activité < 90 j)'
SELECT COUNT(*) AS tokens,
       COUNT(*) FILTER (WHERE NOT revoked AND expires_at > now()) AS valides,
       COUNT(DISTINCT user_id) FILTER (WHERE created_at >= now() - INTERVAL '90 days') AS utilisateurs_token_cree_90j
FROM public.refresh_tokens;

\echo
\echo '#### K1.k — Activité MESURÉE (public.user_daily_activity) : utilisateurs et tenants actifs < 30 j et < 90 j'
\if :has_activity
SELECT COUNT(DISTINCT user_id) FILTER (WHERE activity_date >= current_date - 30)   AS utilisateurs_actifs_30j,
       COUNT(DISTINCT tenant_id) FILTER (WHERE activity_date >= current_date - 30) AS tenants_actifs_30j,
       COUNT(DISTINCT user_id) FILTER (WHERE activity_date >= current_date - 90)   AS utilisateurs_actifs_90j,
       COUNT(DISTINCT tenant_id) FILTER (WHERE activity_date >= current_date - 90) AS tenants_actifs_90j,
       MIN(activity_date) AS premiere_date_connue
FROM public.user_daily_activity;
\else
\echo 'user_daily_activity : non mesuré (objet absent)'
\endif

\echo
\echo '#### K1.l — Paiements, événements de plan et suppressions de compte (public)'
\if :has_payments
\echo '-- tenant_payments par méthode (paiements non annulés)'
SELECT method, COUNT(*) AS paiements, COUNT(DISTINCT tenant_id) AS tenants, SUM(amount_xaf) AS somme_xaf
FROM public.tenant_payments WHERE voided_at IS NULL
GROUP BY 1 ORDER BY 1;
\echo '-- tenants couverts par un paiement (period_end >= aujourd hui), paiements annulés'
SELECT COUNT(DISTINCT tenant_id) FILTER (WHERE voided_at IS NULL AND period_end >= current_date) AS tenants_payes_periode_courante,
       COUNT(*) FILTER (WHERE voided_at IS NOT NULL) AS paiements_annules
FROM public.tenant_payments;
\else
\echo 'tenant_payments : non mesuré (objet absent)'
\endif
\if :has_plan_events
\echo '-- tenant_plan_events par cause × plan cible'
SELECT cause, to_plan, COUNT(*) AS evenements, COUNT(DISTINCT tenant_id) AS tenants
FROM public.tenant_plan_events GROUP BY 1, 2 ORDER BY 1, 2;
\else
\echo 'tenant_plan_events : non mesuré (objet absent)'
\endif
\if :has_deletion_log
\echo '-- account_deletion_log par événement × source'
SELECT event, source, COUNT(*) AS evenements, COUNT(DISTINCT tenant_id) AS tenants
FROM public.account_deletion_log GROUP BY 1, 2 ORDER BY 1, 2;
\else
\echo 'account_deletion_log : non mesuré (objet absent)'
\endif
