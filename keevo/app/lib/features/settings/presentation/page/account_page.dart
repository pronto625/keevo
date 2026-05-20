import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../auth/domain/model/account_profile.dart';
import '../../../auth/presentation/provider/auth_provider.dart';

/// AccountPage — "Mon Compte" full-page profile view (Story 8.6 AC2).
///
/// Route: /settings/account
/// Shows: avatar, personal info, optional store section, security (change password).
/// Loads: accountProfileProvider (GET /api/v1/auth/profile).
class AccountPage extends ConsumerWidget {
  const AccountPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profileAsync = ref.watch(accountProfileProvider);
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: cs.scaffoldBg,
      appBar: AppBar(
        title: const Text('Mon Compte'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new_rounded),
          onPressed: () => context.pop(),
        ),
        elevation: 0,
        backgroundColor: AppTheme.primary,
        foregroundColor: Colors.white,
      ),
      body: profileAsync.when(
        loading: () => const _ProfileSkeleton(),
        error: (error, _) => _ErrorBanner(onRetry: () => ref.invalidate(accountProfileProvider)),
        data: (profile) => _ProfileBody(profile: profile),
      ),
    );
  }
}

// ── Profile body ─────────────────────────────────────────────────────────────

class _ProfileBody extends StatelessWidget {
  final AccountProfile profile;
  const _ProfileBody({required this.profile});

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 24, 16, 32),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── Avatar ─────────────────────────────────────────────────────
          Center(child: _Avatar(profile: profile)),
          const SizedBox(height: 24),

          // ── Section Informations personnelles ──────────────────────────
          _SectionLabel('Informations personnelles'),
          const SizedBox(height: 8),
          _ProfileCard(
            children: [
              _ProfileInfoRow(label: 'Prénom', value: profile.firstName ?? '—'),
              const _RowDivider(),
              _ProfileInfoRow(label: 'Nom', value: profile.lastName ?? '—'),
              const _RowDivider(),
              _ProfileInfoRow(label: 'Téléphone', value: profile.phoneNumber),
              const _RowDivider(),
              _RoleBadgeRow(role: profile.role),
            ],
          ),
          const SizedBox(height: 20),

          // ── Section Boutique (EMPLOYEE only) ──────────────────────────
          if (profile.storeId != null) ...[
            _SectionLabel('Boutique'),
            const SizedBox(height: 8),
            _ProfileCard(
              children: [
                _ProfileInfoRow(
                  label: 'Boutique assignée',
                  value: profile.storeName ?? profile.storeId!,
                ),
              ],
            ),
            const SizedBox(height: 20),
          ],

          // ── Section Sécurité ───────────────────────────────────────────
          _SectionLabel('Sécurité'),
          const SizedBox(height: 8),
          _ProfileCard(
            children: [
              _SecurityTile(
                icon: Icons.lock_reset_rounded,
                title: 'Changer mon mot de passe',
                onTap: () => context.push('/settings/change-password'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// ── Avatar ────────────────────────────────────────────────────────────────────

class _Avatar extends StatelessWidget {
  final AccountProfile profile;
  const _Avatar({required this.profile});

  String _initials() {
    final fn = profile.firstName;
    final ln = profile.lastName;
    if (fn != null && fn.isNotEmpty && ln != null && ln.isNotEmpty) {
      return '${fn[0].toUpperCase()}${ln[0].toUpperCase()}';
    }
    if (profile.phoneNumber.isNotEmpty) {
      return profile.phoneNumber[0];
    }
    return '?';
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 72,
      height: 72,
      decoration: BoxDecoration(
        color: AppTheme.primary.withAlpha(77), // 30% alpha
        shape: BoxShape.circle,
      ),
      child: Center(
        child: Text(
          _initials(),
          style: const TextStyle(
            color: Colors.white,
            fontSize: 26,
            fontWeight: FontWeight.bold,
          ),
        ),
      ),
    );
  }
}

// ── Profile card ──────────────────────────────────────────────────────────────

class _ProfileCard extends StatelessWidget {
  final List<Widget> children;
  const _ProfileCard({required this.children});

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

// ── Section label ─────────────────────────────────────────────────────────────

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

// ── Profile info row ──────────────────────────────────────────────────────────

class _ProfileInfoRow extends StatelessWidget {
  final String label;
  final String value;
  const _ProfileInfoRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      child: Row(
        children: [
          Expanded(
            flex: 2,
            child: Text(
              label,
              style: TextStyle(fontSize: 14, color: cs.muted),
            ),
          ),
          Expanded(
            flex: 3,
            child: Text(
              value,
              style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
              ),
              textAlign: TextAlign.end,
            ),
          ),
        ],
      ),
    );
  }
}

// ── Role badge row ────────────────────────────────────────────────────────────

class _RoleBadgeRow extends StatelessWidget {
  final String role;
  const _RoleBadgeRow({required this.role});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final isOwner = role == 'OWNER';
    final badgeColor = isOwner ? const Color(0xFFFFE066) : const Color(0xFFD0EBFF);
    final textColor = isOwner ? const Color(0xFF664D03) : const Color(0xFF1864AB);
    final label = isOwner ? 'Propriétaire' : 'Employé';

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          Expanded(
            flex: 2,
            child: Text('Rôle', style: TextStyle(fontSize: 14, color: cs.muted)),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: badgeColor,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Text(
              label,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: textColor,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Security tile ──────────────────────────────────────────────────────────────

class _SecurityTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final VoidCallback onTap;
  const _SecurityTile({
    required this.icon,
    required this.title,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: AppTheme.iconBlueBg,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, color: AppTheme.iconBlue, size: 20),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Text(
                  title,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              Icon(Icons.chevron_right, color: cs.muted, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Row divider ────────────────────────────────────────────────────────────────

class _RowDivider extends StatelessWidget {
  const _RowDivider();

  @override
  Widget build(BuildContext context) {
    return const Divider(indent: 16, endIndent: 16, height: 1, thickness: 0.5);
  }
}

// ── Skeleton loader ────────────────────────────────────────────────────────────

class _ProfileSkeleton extends StatelessWidget {
  const _ProfileSkeleton();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final shimmerColor = cs.surfaceContainerHighest.withAlpha(120);

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 24, 16, 32),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Center(
            child: Container(
              width: 72,
              height: 72,
              decoration: BoxDecoration(
                color: shimmerColor,
                shape: BoxShape.circle,
              ),
            ),
          ),
          const SizedBox(height: 24),
          Container(height: 14, width: 140, color: shimmerColor),
          const SizedBox(height: 8),
          Container(
            height: 160,
            decoration: BoxDecoration(
              color: shimmerColor,
              borderRadius: BorderRadius.circular(16),
            ),
          ),
          const SizedBox(height: 20),
          Container(height: 14, width: 80, color: shimmerColor),
          const SizedBox(height: 8),
          Container(
            height: 56,
            decoration: BoxDecoration(
              color: shimmerColor,
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Error banner ────────────────────────────────────────────────────────────────

class _ErrorBanner extends StatelessWidget {
  final VoidCallback onRetry;
  const _ErrorBanner({required this.onRetry});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline_rounded, size: 48, color: cs.error),
            const SizedBox(height: 12),
            Text(
              'Impossible de charger le profil',
              style: TextStyle(fontSize: 15, color: cs.onSurface),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            ElevatedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Réessayer'),
            ),
          ],
        ),
      ),
    );
  }
}
