import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_constants.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../stores/domain/model/store_model.dart';
import '../../../stores/domain/model/store_type.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../../stores/presentation/provider/store_provider.dart';

/// SettingsPage — "Plus" tab → Paramètres.
///
/// UX spec: Plus > Paramètres → Boutiques, Abonnement, Notifications, Aide.
/// Gradient header Indigo Sky, cards 16dp radius, Material 3.
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
          // ── Gradient header ─────────────────────────────────────────────
          SliverAppBar(
            expandedHeight: 140,
            pinned: true,
            automaticallyImplyLeading: false,
            elevation: 0,
            backgroundColor: Colors.transparent,
            flexibleSpace: FlexibleSpaceBar(
              background: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: [AppTheme.primary, AppTheme.primary.withAlpha(180)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                ),
                child: SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
                    child: Row(
                      children: [
                        // Avatar circle
                        Container(
                          width: 48,
                          height: 48,
                          decoration: BoxDecoration(
                            color: Colors.white.withAlpha(51),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(
                            Icons.person_rounded,
                            color: Colors.white,
                            size: 28,
                          ),
                        ),
                        const SizedBox(width: 14),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Text(
                                phone ?? 'Chargement...',
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 16,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 8, vertical: 2),
                                decoration: BoxDecoration(
                                  color: isOwner
                                      ? const Color(0xFFFFE066)
                                      : Colors.white.withAlpha(51),
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: Text(
                                  isOwner ? 'Propriétaire' : 'Employé',
                                  style: TextStyle(
                                    fontSize: 11,
                                    fontWeight: FontWeight.w700,
                                    color: isOwner
                                        ? const Color(0xFF664D03)
                                        : Colors.white,
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
                // ── Section : Gestion (OWNER only — AC5) ────────────────
                if (isOwner) ...[
                const _SectionLabel('Gestion'),
                const SizedBox(height: 8),
                _SettingsCard(
                  children: [
                    _SettingsTile(
                      icon: Icons.storefront_rounded,
                      iconColor: AppTheme.primary,
                      iconBg: AppTheme.iconBlueBg,
                      title: 'Mes boutiques',
                      subtitle: 'Boutiques, entrepôts et types',
                      onTap: () => context.push('/stores'),
                    ),
                    const _Divider(),
                    const _ActiveStoreTile(),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.inventory_2_rounded,
                      iconColor: AppTheme.iconTeal,
                      iconBg: AppTheme.iconTealBg,
                      title: 'Stock multi-boutiques',
                      subtitle: 'Vue centralisée des stocks par boutique',
                      onTap: () => context.push('/stock/overview'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.assignment_outlined,
                      iconColor: AppTheme.iconAmber,
                      iconBg: AppTheme.iconAmberBg,
                      title: 'Inventaire',
                      subtitle: 'Lancer et suivre les sessions d\'inventaire',
                      onTap: () => context.push('/inventory'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.card_membership_rounded,
                      iconColor: AppTheme.iconPurple,
                      iconBg: AppTheme.iconPurpleBg,
                      title: 'Abonnement',
                      subtitle: 'Plan actif, limites et mise à niveau',
                      onTap: () => context.push('/settings/subscription'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.group_rounded,
                      iconColor: AppTheme.iconBlue,
                      iconBg: AppTheme.iconBlueBg,
                      title: 'Équipe',
                      subtitle: 'Employés, rôles et accès',
                      onTap: () => context.push('/settings/team'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.history_rounded,
                      iconColor: AppTheme.iconIndigo,
                      iconBg: AppTheme.iconIndigoBg,
                      title: 'Journal d\'audit',
                      subtitle: 'Historique complet des opérations',
                      onTap: () => context.push('/audit'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.sync_rounded,
                      iconColor: AppTheme.iconBlue,
                      iconBg: AppTheme.iconBlueBg,
                      title: 'Synchronisation',
                      subtitle: 'Historique, appareils et file d\'attente',
                      onTap: () => context.push('/settings/sync'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.people_rounded,
                      iconColor: AppTheme.iconCyan,
                      iconBg: AppTheme.iconCyanBg,
                      title: 'Clients',
                      subtitle: 'Gérez votre carnet de clients',
                      onTap: () => context.push('/clients'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.local_shipping_rounded,
                      iconColor: AppTheme.iconOrange,
                      iconBg: AppTheme.iconOrangeBg,
                      title: 'Fournisseurs',
                      subtitle: 'Contacts et approvisionnement',
                      onTap: () => context.push('/suppliers'),
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
                        icon: Icons.inventory_2_rounded,
                        iconColor: AppTheme.iconTeal,
                        iconBg: AppTheme.iconTealBg,
                        title: 'Stock multi-boutiques',
                        subtitle: 'Vue centralisée des stocks par boutique',
                        onTap: () => context.push('/stock/overview'),
                      ),
                      const _Divider(),
                      _SettingsTile(
                        icon: Icons.assignment_outlined,
                        iconColor: AppTheme.iconAmber,
                        iconBg: AppTheme.iconAmberBg,
                        title: 'Inventaire',
                        subtitle: 'Lancer et suivre les sessions d\'inventaire',
                        onTap: () => context.push('/inventory'),
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
                      iconBg: AppTheme.iconOrangeBg,
                      title: 'Notifications',
                      subtitle: 'Heure du rapport, alertes stock',
                      onTap: () {},
                      badge: 'Bientôt',
                    ),
                    const _Divider(),
                    if (isOwner)
                    _SettingsTile(
                      icon: Icons.bar_chart_rounded,
                      iconColor: AppTheme.success,
                      iconBg: const Color(0xFFE8F5E9),
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
                      iconBg: AppTheme.iconGreyBg,
                      title: 'Aide & Tutoriels',
                      subtitle: 'Guides et questions fréquentes',
                      onTap: () {},
                      badge: 'Bientôt',
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.bug_report_outlined,
                      iconColor: AppTheme.iconGrey,
                      iconBg: AppTheme.iconGreyBg,
                      title: 'Signaler un problème',
                      subtitle: 'Envoyer un retour à l\'équipe Keevo',
                      onTap: () {},
                      badge: 'Bientôt',
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
                      color: cs.onSurface.withAlpha(100),
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
    if (confirmed != true) return;

    const storage = FlutterSecureStorage();
    await storage.deleteAll();

    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.remove(kUserRoleKey);
    await prefs.remove(kUserPhoneKey);
    await prefs.remove(kPasswordChangeRequiredKey);
    await prefs.remove(kOnboardingWizardSeenKey);

    ref.invalidate(currentUserRoleProvider);
    ref.invalidate(currentUserPhoneProvider);

    if (context.mounted) context.go('/auth/login');
  }
}

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
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: cs.errorContainer_,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  Icons.logout_rounded,
                  color: cs.error,
                  size: 20,
                ),
              ),
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
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: cs.shadow.withAlpha(13),
            blurRadius: 12,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(children: children),
    );
  }
}

class _SettingsTile extends StatelessWidget {
  final IconData icon;
  final Color iconColor;
  final Color iconBg;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  final String? badge;

  const _SettingsTile({
    required this.icon,
    required this.iconColor,
    required this.iconBg,
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
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: iconBg,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, color: iconColor, size: 20),
              ),
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
                Icon(Icons.chevron_right,
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
      icon: Icons.store_rounded,
      iconColor: AppTheme.iconTeal,
      iconBg: AppTheme.iconTealBg,
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
                style: theme.textTheme.titleLarge
                    ?.copyWith(fontWeight: FontWeight.bold),
              ),
            ),
          ),
          const Divider(height: 1),
          // "Toutes les boutiques" option
          ListTile(
            leading: Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: const Color(0xFFD0EBFF),
                borderRadius: BorderRadius.circular(10),
              ),
              child: const Icon(Icons.all_inclusive,
                  color: AppTheme.iconBlue, size: 20),
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
              leading: Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: store.type == StoreType.warehouse
                      ? AppTheme.iconAmberBg
                      : AppTheme.iconTealBg,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  store.type == StoreType.warehouse
                      ? Icons.warehouse_rounded
                      : Icons.storefront_rounded,
                  color: store.type == StoreType.warehouse
                      ? AppTheme.iconAmber
                      : AppTheme.iconTeal,
                  size: 20,
                ),
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
