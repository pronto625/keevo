import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';

/// SettingsPage — "Plus" tab → Paramètres.
///
/// UX spec: Plus > Paramètres → Boutiques, Abonnement, Notifications, Aide.
/// Gradient header Indigo Sky, cards 16dp radius, Material 3.
class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
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
                child: const SafeArea(
                  child: Padding(
                    padding: EdgeInsets.fromLTRB(20, 16, 20, 0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Plus',
                          style: TextStyle(
                            color: Colors.white70,
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                            letterSpacing: 0.5,
                          ),
                        ),
                        SizedBox(height: 4),
                        Text(
                          'Paramètres',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 26,
                            fontWeight: FontWeight.bold,
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
                // ── Section : Gestion ───────────────────────────────────
                const _SectionLabel('Gestion'),
                const SizedBox(height: 8),
                _SettingsCard(
                  children: [
                    _SettingsTile(
                      icon: Icons.storefront_rounded,
                      iconColor: AppTheme.primary,
                      iconBg: const Color(0xFFD0EBFF),
                      title: 'Mes boutiques',
                      subtitle: 'Boutiques, entrepôts et types',
                      onTap: () => context.push('/stores'),
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.card_membership_rounded,
                      iconColor: const Color(0xFF9B59B6),
                      iconBg: const Color(0xFFF3E5F5),
                      title: 'Abonnement',
                      subtitle: 'Plan actif, limites et mise à niveau',
                      onTap: () => context.push('/settings/subscription'),
                    ),
                  ],
                ),

                const SizedBox(height: 20),

                // ── Section : Préférences ────────────────────────────────
                const _SectionLabel('Préférences'),
                const SizedBox(height: 8),
                _SettingsCard(
                  children: [
                    _SettingsTile(
                      icon: Icons.notifications_outlined,
                      iconColor: const Color(0xFFE67E22),
                      iconBg: const Color(0xFFFFF3E0),
                      title: 'Notifications',
                      subtitle: 'Heure du rapport, alertes stock',
                      onTap: () {},
                      badge: 'Bientôt',
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.bar_chart_rounded,
                      iconColor: AppTheme.success,
                      iconBg: const Color(0xFFE8F5E9),
                      title: 'Rapports',
                      subtitle: 'Types de rapports activés',
                      onTap: () {},
                      badge: 'Bientôt',
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
                      iconColor: const Color(0xFF868E96),
                      iconBg: const Color(0xFFF1F3F5),
                      title: 'Aide & Tutoriels',
                      subtitle: 'Guides et questions fréquentes',
                      onTap: () {},
                      badge: 'Bientôt',
                    ),
                    const _Divider(),
                    _SettingsTile(
                      icon: Icons.bug_report_outlined,
                      iconColor: const Color(0xFF868E96),
                      iconBg: const Color(0xFFF1F3F5),
                      title: 'Signaler un problème',
                      subtitle: 'Envoyer un retour à l\'équipe Keevo',
                      onTap: () {},
                      badge: 'Bientôt',
                    ),
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
}

// ── Private widgets ─────────────────────────────────────────────────────────

class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel(this.text);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 2),
      child: Text(
        text.toUpperCase(),
        style: const TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.8,
          color: Color(0xFF868E96),
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
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withAlpha(13),
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
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: Color(0xFF212529),
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: const TextStyle(
                        fontSize: 12,
                        color: Color(0xFF868E96),
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
                    color: const Color(0xFFF1F3F5),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                    badge!,
                    style: const TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w600,
                      color: Color(0xFF868E96),
                    ),
                  ),
                )
              else
                const Icon(Icons.chevron_right,
                    color: Color(0xFFADB5BD), size: 20),
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
