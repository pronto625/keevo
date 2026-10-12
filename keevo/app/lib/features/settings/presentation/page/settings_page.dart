import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../../auth/presentation/provider/sign_out_service.dart';
import '../../../stores/domain/model/store_model.dart';
import '../../../stores/domain/model/store_type.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../../stores/presentation/provider/store_provider.dart';

/// SettingsPage — "Plus" tab → Paramètres.
///
/// UX spec: Plus > Paramètres → Boutiques, Abonnement, Notifications, Aide.
/// Neutral header, soft cards 16dp radius, Material 3.
/// AC5: EMPLOYEE sees only Aide & Support section. OWNER-only tiles hidden.
class SettingsPage extends ConsumerWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final role = ref.watch(currentUserRoleProvider);
    final phone = ref.watch(currentUserPhoneProvider);
    final isOwner = role == 'OWNER';

    return Scaffold(
      backgroundColor: cs.scaffoldBg,
      body: CustomScrollView(
        slivers: [
          // ── Header ──────────────────────────────────────────────────────
          // Fond neutre plutôt que bandeau dégradé ; même contenu (avatar,
          // téléphone, rôle).
          SliverAppBar(
            expandedHeight: 140,
            pinned: true,
            automaticallyImplyLeading: false,
            leading: context.canPop()
                ? IconButton(
                    icon: Icon(Icons.arrow_back_ios_new_rounded,
                        color: cs.onSurface),
                    onPressed: () => context.pop(),
                  )
                : null,
            elevation: 0,
            scrolledUnderElevation: 0,
            backgroundColor: cs.surface,
            flexibleSpace: FlexibleSpaceBar(
              background: ColoredBox(
                color: cs.surface,
                child: SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
                    child: Row(
                      children: [
                        const SoftIconBadge(
                          icon: Icons.person_outline_rounded,
                          size: 48,
                        ),
                        const SizedBox(width: 14),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Text(
                                phone ?? 'Chargement...',
                                style: TextStyle(
                                  color: cs.onSurface,
                                  fontSize: 16,
                                  fontWeight: FontWeight.w700,
                                  letterSpacing: -0.2,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 8, vertical: 2),
                                decoration: BoxDecoration(
                                  color: isOwner
                                      ? cs.warningContainer
                                      : cs.surfaceContainerHighest,
                                  borderRadius: BorderRadius.circular(20),
                                ),
                                child: Text(
                                  isOwner ? 'Propriétaire' : 'Employé',
                                  style: TextStyle(
                                    fontSize: 11,
                                    fontWeight: FontWeight.w700,
                                    color: isOwner
                                        ? cs.onWarningContainer
                                        : cs.onSurfaceVariant,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),

          // ── Body ────────────────────────────────────────────────────────
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 20, 16, 32),
            sliver: SliverList(
              delegate: SliverChildListDelegate([
                // ── Section : Compte (OWNER + EMPLOYEE — AC1) ───────────
                const _SectionLabel('Compte'),
                const SizedBox(height: 8),
                _SettingsCard(
                  children: [
                    _SettingsTile(
                      icon: Icons.manage_accounts_outlined,
                      iconColor: AppTheme.iconBlue,
                      title: 'Mon Compte',
                      subtitle: phone ?? 'Chargement...',
                      onTap: () => context.push('/settings/account'),
                    ),
                  ],
                ),
                const SizedBox(height: 20),

                // ── Section : Gestion (OWNER only — AC5) ────────────────
                if (isOwner) ...[
                const _SectionLabel('Gestion'),
                const SizedBox(height: 8),
                _SettingsCard(
                  children: [
                    _SettingsTile(
                      icon: Icons.storefront_outlined,
                      iconColor: AppTheme.primary,
                      title: 'Mes boutiques',
                      subtitle: 'Boutiques, entrepôts et types',
                      onTap: () => context.push('/stores'),
                    ),
                    const _Divider(),
                    const _ActiveStoreTile(),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.inventory_2_outlined,
                      iconColor: AppTheme.iconTeal,
                      title: 'Stock multi-boutiques',
                      subtitle: 'Vue centralisée des stocks par boutique',
                      onTap: () => context.push('/stock/overview'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.assignment_outlined,
                      iconColor: AppTheme.iconAmber,
                      title: 'Inventaire',
                      subtitle: 'Lancer et suivre les sessions d\'inventaire',
                      onTap: () => context.push('/inventory'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.card_membership_outlined,
                      iconColor: AppTheme.iconPurple,
                      title: 'Abonnement',
                      subtitle: 'Plan actif, limites et mise à niveau',
                      onTap: () => context.push('/settings/subscription'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.group_outlined,
                      iconColor: AppTheme.iconBlue,
                      title: 'Équipe',
                      subtitle: 'Employés, rôles et accès',
                      onTap: () => context.push('/settings/team'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.history_rounded,
                      iconColor: AppTheme.iconIndigo,
                      title: 'Journal d\'audit',
                      subtitle: 'Historique complet des opérations',
                      onTap: () => context.push('/audit'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.sync_rounded,
                      iconColor: AppTheme.iconBlue,
                      title: 'Synchronisation',
                      subtitle: 'Historique, appareils et file d\'attente',
                      onTap: () => context.push('/settings/sync'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.people_outline_rounded,
                      iconColor: AppTheme.iconCyan,
                      title: 'Clients',
                      subtitle: 'Gérez votre carnet de clients',
                      onTap: () => context.push('/clients'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.local_shipping_outlined,
                      iconColor: AppTheme.iconOrange,
                      title: 'Fournisseurs',
                      subtitle: 'Contacts et approvisionnement',
                      onTap: () => context.push('/suppliers'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.category_outlined,
                      iconColor: AppTheme.iconPurple,
                      title: 'Catégories',
                      subtitle: 'Gérez les catégories de produits',
                      onTap: () => context.push('/settings/categories'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.swap_horiz_rounded,
                      iconColor: AppTheme.iconTeal,
                      title: 'Transferts',
                      subtitle: 'Historique des transferts de stock',
                      onTap: () => context.push('/stock/transfers'),
                    ),
                  ],
                ),
                const SizedBox(height: 20),
                ],

                // ── Section : Consultation stock (EMPLOYEE — read-only) ──
                if (!isOwner) ...[
                  const _SectionLabel('Consultation'),
                  const SizedBox(height: 8),
                  _SettingsCard(
                    children: [
                      _SettingsTile(
                        icon: Icons.inventory_2_outlined,
                        iconColor: AppTheme.iconTeal,
                        title: 'Stock multi-boutiques',
                        subtitle: 'Vue centralisée des stocks par boutique',
                        onTap: () => context.push('/stock/overview'),
                      ),
                      const _Divider(),
                      _SettingsTile(
                        icon: Icons.assignment_outlined,
                        iconColor: AppTheme.iconAmber,
                        title: 'Inventaire',
                        subtitle: 'Lancer et suivre les sessions d\'inventaire',
                        onTap: () => context.push('/inventory'),
                      ),
                      const _Divider(),
                      _SettingsTile(
                        icon: Icons.swap_horiz_rounded,
                        iconColor: AppTheme.iconTeal,
                        title: 'Transferts',
                        subtitle: 'Historique des transferts de stock',
                        onTap: () => context.push('/stock/transfers'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 20),
                ],

                // ── Section : Préférences ────────────────────────────────
                const _SectionLabel('Préférences'),
                const SizedBox(height: 8),
                _SettingsCard(
                  children: [
                    _SettingsTile(
                      icon: Icons.notifications_outlined,
                      iconColor: AppTheme.iconOrange,
                      title: 'Notifications',
                      subtitle: 'Heure du rapport, alertes stock',
                      onTap: () {},
                      badge: 'Bientôt',
                    ),
                    const _Divider(),
                    if (isOwner)
                    _SettingsTile(
                      icon: Icons.bar_chart_rounded,
                      iconColor: cs.success,
                      title: 'Rapports & WhatsApp',
                      subtitle: 'Configurer les rapports automatiques',
                      onTap: () => context.push('/settings/reports'),
                    ),
                  ],
                ),

                const SizedBox(height: 20),

                // ── Section : Aide ───────────────────────────────────────
                const _SectionLabel('Support'),
                const SizedBox(height: 8),
                _SettingsCard(
                  children: [
                    _SettingsTile(
                      icon: Icons.help_outline_rounded,
                      iconColor: AppTheme.iconGrey,
                      title: 'Aide & Tutoriels',
                      subtitle: 'Guides et questions fréquentes',
                      onTap: () {},
                      badge: 'Bientôt',
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.bug_report_outlined,
                      iconColor: AppTheme.iconGrey,
                      title: 'Signaler un problème',
                      subtitle: 'Envoyer un retour à l\'équipe Keevo',
                      // Story 14.5 (FR92): the screen the user is on at the
                      // tap travels as `from` — `uri.path`, never `.name`
                      // (no route is named in this router).
                      onTap: () {
                        final from = GoRouterState.of(context).uri.path;
                        context.push(
                          '/settings/feedback?from=${Uri.encodeQueryComponent(from)}',
                        );
                      },
                    ),
                  ],
                ),

                const SizedBox(height: 20),

                // ── Déconnexion ──────────────────────────────────────────
                _SettingsCard(
                  children: [
                    _LogoutTile(onLogout: () => _logout(context, ref)),
                  ],
                ),

                const SizedBox(height: 24),
                Center(
                  child: Text(
                    'Keevo — Augmenter, ne pas Remplacer',
                    style: TextStyle(
                      color: cs.onSurfaceVariant,
                      fontSize: 12,
                    ),
                  ),
                ),
              ]),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _logout(BuildContext context, WidgetRef ref) async {
    // One sign-out at a time: a second tap while the queue is being sent
    // would start a second push and a second sign-out.
    final inProgress = ref.read(_signOutInProgressProvider.notifier);
    if (inProgress.state) return;
    inProgress.state = true;
    try {
      await _runLogout(context, ref);
    } finally {
      inProgress.state = false;
    }
  }

  Future<void> _runLogout(BuildContext context, WidgetRef ref) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Se déconnecter ?'),
        content: const Text(
            'Vous devrez vous reconnecter pour accéder à votre compte.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Annuler'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(
              foregroundColor: AppTheme.errorColor,
            ),
            child: const Text('Déconnecter'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;

    final signOut = ref.read(signOutServiceProvider);
    // Unsynced sales stay on this device and are sent at the next sign-in of
    // the same account, but another account signing in here would clear them.
    // The push can take several seconds on a slow network: show it.
    final allSent = await _withProgress(
      context,
      'Envoi des opérations…',
      () async {
        try {
          return await signOut.flushPendingOperations();
        } catch (_) {
          return false;
        }
      },
    );
    if (!allSent) {
      // v1s-16-15: blocked operations are never sent automatically.
      var blocked = 0;
      try {
        blocked =
            await ref.read(syncServiceProvider).quarantinedOperationCount();
      } catch (_) {}
      if (!context.mounted) return;
      final proceed = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Opérations non synchronisées'),
          content: Text(blocked > 0
              ? 'Certaines opérations ont été refusées par le serveur et sont '
                  'bloquées : elles ne seront pas envoyées automatiquement. '
                  'Traitez-les dans Paramètres › Synchronisation (opérations '
                  'bloquées). Elles seront perdues si un autre compte se '
                  'connecte sur ce téléphone.'
              : "Certaines opérations n'ont pas encore été envoyées au serveur. "
                  'Elles seront envoyées à votre prochaine connexion sur ce '
                  'téléphone avec ce compte, mais seront perdues si un autre '
                  'compte s’y connecte.'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Rester connecté'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(ctx, true),
              style: TextButton.styleFrom(
                foregroundColor: AppTheme.errorColor,
              ),
              child: const Text('Déconnecter quand même'),
            ),
          ],
        ),
      );
      if (proceed != true || !context.mounted) return;
    }

    try {
      await _withProgress(context, 'Déconnexion…', signOut.signOut);
    } finally {
      // Even if a cleanup step fails, the user asked to leave.
      if (context.mounted) context.go('/auth/login');
    }
  }

  /// Runs [task] behind a non-dismissible progress dialog.
  Future<T> _withProgress<T>(
      BuildContext context, String label, Future<T> Function() task) async {
    final navigator = Navigator.of(context, rootNavigator: true);
    unawaited(showDialog<void>(
      context: context,
      useRootNavigator: true,
      barrierDismissible: false,
      builder: (_) => PopScope(
        canPop: false,
        child: AlertDialog(
          content: Row(children: [
            const SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(width: 16),
            Expanded(child: Text(label)),
          ]),
        ),
      ),
    ));
    try {
      return await task();
    } finally {
      navigator.pop();
    }
  }
}

/// True while a sign-out runs (re-entry guard for the logout tile).
final _signOutInProgressProvider = StateProvider<bool>((_) => false);

// ── Private widgets ─────────────────────────────────────────────────────────

class _LogoutTile extends StatelessWidget {
  final VoidCallback onLogout;
  const _LogoutTile({required this.onLogout});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onLogout,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              SoftIconBadge(icon: Icons.logout_rounded, color: cs.error),
              const SizedBox(width: 14),
              Expanded(
                child: Text(
                  'Se déconnecter',
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: cs.error,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel(this.text);

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 2),
      child: Text(
        text.toUpperCase(),
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.8,
          color: cs.muted,
        ),
      ),
    );
  }
}

class _SettingsCard extends StatelessWidget {
  final List<Widget> children;
  const _SettingsCard({required this.children});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      // cardBg plutôt que surface : en sombre, surface == fond de page et
      // la carte disparaîtrait.
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        boxShadow: AppTheme.softShadow(cs),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(children: children),
    );
  }
}

class _SettingsTile extends StatelessWidget {
  final IconData icon;
  final Color iconColor;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  final String? badge;

  const _SettingsTile({
    required this.icon,
    required this.iconColor,
    required this.title,
    required this.subtitle,
    required this.onTap,
    this.badge,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: badge != null ? null : onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              // Teinte par ligne conservée (repère visuel), diluée par le
              // badge pour rester lisible en mode sombre.
              SoftIconBadge(icon: icon, color: iconColor),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: cs.onSurface,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: TextStyle(
                        fontSize: 12,
                        color: cs.muted,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              if (badge != null)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: cs.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                    badge!,
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w600,
                      color: cs.muted,
                    ),
                  ),
                )
              else
                Icon(Icons.chevron_right_rounded,
                    color: cs.muted, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

class _Divider extends StatelessWidget {
  const _Divider();

  @override
  Widget build(BuildContext context) {
    return const Divider(indent: 70, endIndent: 0, height: 1, thickness: 0.5);
  }
}

// ── Boutique active tile ────────────────────────────────────────────────────

class _ActiveStoreTile extends ConsumerWidget {
  const _ActiveStoreTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final activeStoreId = ref.watch(activeStoreIdProvider);
    final storesAsync = ref.watch(storeListNotifierProvider);

    final subtitle = storesAsync.maybeWhen(
      data: (stores) {
        if (activeStoreId == null) return 'Toutes les boutiques';
        return stores.where((s) => s.id == activeStoreId).firstOrNull?.name ??
            'Boutique inconnue';
      },
      orElse: () => activeStoreId != null ? 'Chargement...' : 'Toutes les boutiques',
    );

    return _SettingsTile(
      icon: Icons.store_outlined,
      iconColor: AppTheme.iconTeal,
      title: 'Boutique active',
      subtitle: subtitle,
      onTap: () => _showStorePicker(
        context,
        ref,
        activeStoreId,
        storesAsync.valueOrNull ?? [],
      ),
    );
  }

  void _showStorePicker(
    BuildContext context,
    WidgetRef ref,
    String? activeStoreId,
    List<StoreModel> stores,
  ) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (sheetCtx) => _StorePickerSheet(
        stores: stores,
        activeStoreId: activeStoreId,
        onSelect: (id) {
          ref.read(activeStoreIdProvider.notifier).setActiveStore(id);
          Navigator.pop(sheetCtx);
        },
      ),
    );
  }
}

class _StorePickerSheet extends StatelessWidget {
  final List<StoreModel> stores;
  final String? activeStoreId;
  final void Function(String?) onSelect;

  const _StorePickerSheet({
    required this.stores,
    required this.activeStoreId,
    required this.onSelect,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Handle bar
          Container(
            margin: const EdgeInsets.only(top: 12),
            width: 36,
            height: 4,
            decoration: BoxDecoration(
              color: theme.colorScheme.outlineVariant,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Boutique active',
                style: theme.textTheme.titleLarge?.copyWith(
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.2,
                ),
              ),
            ),
          ),
          const Divider(height: 1),
          // "Toutes les boutiques" option
          ListTile(
            leading: const SoftIconBadge(
              icon: Icons.all_inclusive_rounded,
              color: AppTheme.iconBlue,
            ),
            title: const Text('Toutes les boutiques'),
            subtitle: const Text('Afficher tous les produits'),
            trailing: activeStoreId == null
                ? Icon(Icons.check_circle, color: theme.colorScheme.primary)
                : null,
            onTap: () => onSelect(null),
          ),
          const Divider(height: 1, indent: 72),
          // Active store rows
          ...stores.map(
            (store) => ListTile(
              leading: SoftIconBadge(
                icon: store.type == StoreType.warehouse
                    ? Icons.warehouse_outlined
                    : Icons.storefront_outlined,
                color: store.type == StoreType.warehouse
                    ? AppTheme.iconAmber
                    : AppTheme.iconTeal,
              ),
              title: Text(store.name),
              subtitle: Text(store.type.displayName),
              trailing: store.id == activeStoreId
                  ? Icon(Icons.check_circle, color: theme.colorScheme.primary)
                  : null,
              onTap: () => onSelect(store.id),
            ),
          ),
          const SizedBox(height: 16),
        ],
      ),
    );
  }
}
